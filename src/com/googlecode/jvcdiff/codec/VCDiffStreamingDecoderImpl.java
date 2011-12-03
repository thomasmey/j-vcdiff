package com.googlecode.jvcdiff.codec;

import static com.googlecode.jvcdiff.codec.VCDiffHeaderParser.RESULT_ERROR;
import static com.googlecode.jvcdiff.codec.VCDiffHeaderParser.RESULT_SUCCESS;
import static com.googlecode.jvcdiff.codec.VCDiffHeaderParser.RESULT_END_OF_DATA;
import static com.googlecode.jvcdiff.codec.VCDiffHeaderParser.VCD_CODETABLE;
import static com.googlecode.jvcdiff.codec.VCDiffHeaderParser.VCD_DECOMPRESS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.jvcdiff.VCDiffAddressCache;
import com.googlecode.jvcdiff.VCDiffAddressCacheImpl;
import com.googlecode.jvcdiff.VCDiffCodeTableData;
import com.googlecode.jvcdiff.mina_buffer.IoBuffer;

public class VCDiffStreamingDecoderImpl implements VCDiffStreamingDecoder {
	private static final Logger LOGGER = LoggerFactory.getLogger(VCDiffStreamingDecoderImpl.class);

	// The default maximum target file size (and target window size) if
	// SetMaximumTargetFileSize() is not called.
	public static final int kDefaultMaximumTargetFileSize = 67108864;  // 64 MB

	// The largest value that can be passed to SetMaximumTargetWindowSize().
	// Using a larger value will result in an error.
	public static final int kTargetSizeLimit = Integer.MAX_VALUE;

	// A constant that is the default value for planned_target_file_size_,
	// indicating that the decoder does not have an expected length
	// for the target data.
	public static final int kUnlimitedBytes = -3;

	// Contents and length of the source (dictionary) data.
	private byte[] dictionary_ptr_;

	// This string will be used to store any unparsed bytes left over when
	// DecodeChunk() reaches the end of its input and returns RESULT_END_OF_DATA.
	// It will also be used to concatenate those unparsed bytes with the data
	// supplied to the next call to DecodeChunk(), so that they appear in
	// contiguous memory.
	private final IoBuffer unparsed_bytes_ = IoBuffer.allocate(256);

	// The portion of the target file that has been decoded so far.  This will be
	// used to fill the output string for DecodeChunk(), and will also be used to
	// execute COPY instructions that reference target data.  Since the source
	// window can come from a range of addresses in the previously decoded target
	// data, the entire target file needs to be available to the decoder, not just
	// the current target window.
	
	// Originally a String
	private final IoBuffer decoded_target_ = IoBuffer.allocate(512);

	// The VCDIFF version byte (also known as "header4") from the
	// delta file header.
	private byte vcdiff_version_code_;

	private VCDiffDeltaFileWindow delta_window_;

	private VCDiffAddressCache addr_cache_;

	// Will be NULL unless a custom code table has been defined.
	private VCDiffCodeTableData custom_code_table_;

	// Used to receive the decoded custom code table.
	private IoBuffer custom_code_table_string_;

	// If a custom code table is specified, it will be expressed
	// as an embedded VCDIFF delta file which uses the default code table
	// as the source file (dictionary).  Use a child decoder object
	// to decode that delta file.
	private VCDiffStreamingDecoderImpl custom_code_table_decoder_;

	// If set, then the decoder is expecting *exactly* this number of
	// target bytes to be decoded from one or more delta file windows.
	// If this number is exceeded while decoding a window, but was not met
	// before starting on that window, an error will be reported.
	// If FinishDecoding() is called before this number is met, an error
	// will also be reported.  This feature is used for decoding the
	// embedded code table data within a VCDIFF delta file; we want to
	// stop processing the embedded data once the entire code table has
	// been decoded, and treat the rest of the available data as part
	// of the enclosing delta file.
	private int planned_target_file_size_;

	private int maximum_target_file_size_ = kDefaultMaximumTargetFileSize;

	private int maximum_target_window_size_ = kDefaultMaximumTargetFileSize;

	// Contains the sum of the decoded sizes of all target windows seen so far,
	// including the expected total size of the current target window in progress
	// (even if some of the current target window has not yet been decoded.)
	private int total_of_target_window_sizes_;

	// Contains the byte position within decoded_target_ of the first data that
	// has not yet been output by AppendNewOutputText().
	private int decoded_target_output_position_;

	// This value is used to ensure the correct order of calls to the interface
	// functions, i.e., a single call to StartDecoding(), followed by zero or
	// more calls to DecodeChunk(), followed by a single call to
	// FinishDecoding().
	private boolean start_decoding_was_called_;

	// If this value is true then the VCD_TARGET flag can be specified to allow
	// the source segment to be chosen from the previously-decoded target data.
	// (This is the default behavior.)  If it is false, then specifying the
	// VCD_TARGET flag is considered an error, and the decoder does not need to
	// keep in memory any decoded target data prior to the current window.
	private boolean allow_vcd_target_ = true;

	public VCDiffStreamingDecoderImpl() {
		delta_window_.Init(this);
		Reset();
	}

	// Resets all member variables to their initial states.
	public void Reset() {
		start_decoding_was_called_ = false;
		dictionary_ptr_ = null;
		vcdiff_version_code_ = 0;
		planned_target_file_size_ = kUnlimitedBytes;
		total_of_target_window_sizes_ = 0;
		addr_cache_ = null;
		custom_code_table_ = null;
		custom_code_table_decoder_ = null;
		delta_window_.Reset();
		decoded_target_output_position_ = 0;
	}

	// These functions are identical to their counterparts
	// in VCDiffStreamingDecoder.
	//
	public void StartDecoding(byte[] dictionary_ptr) {
		if (start_decoding_was_called_) {
			LOGGER.error("StartDecoding() called twice without FinishDecoding()");
			return;
		}

		unparsed_bytes_.clear();
		decoded_target_.clear();  // delta_window_.Reset() depends on this
		Reset();
		dictionary_ptr_ = dictionary_ptr;
		start_decoding_was_called_ = true;
	}

	public boolean DecodeChunk(byte[] data, int offset, int len, OutputStream output_string) {
		if (!start_decoding_was_called_) {
			LOGGER.error("DecodeChunk() called without StartDecoding()");
			Reset();
			return false;
		}
		IoBuffer parseable_chunk;
		if (unparsed_bytes_.limit() > 0) {
			unparsed_bytes_.put(data, offset, len);
			parseable_chunk = unparsed_bytes_.duplicate();
			parseable_chunk.flip();
		} else {
			 parseable_chunk = IoBuffer.wrap(data, offset, len);
		}

		int result = ReadDeltaFileHeader(parseable_chunk);
		if (RESULT_SUCCESS == result) {
			result = ReadCustomCodeTable(parseable_chunk);
		}
		if (RESULT_SUCCESS == result) {
			while (parseable_chunk.hasRemaining()) {
				result = delta_window_.DecodeWindow(parseable_chunk);
				if (RESULT_SUCCESS != result) {
					break;
				}
				if (ReachedPlannedTargetFileSize()) {
					// Found exactly the length we expected.  Stop decoding.
					break;
				}
				if (!allow_vcd_target()) {
					// VCD_TARGET will never be used to reference target data before the
					// start of the current window, so flush and clear the contents of
					// decoded_target_.
					FlushDecodedTarget(output_string);
				}
			}
		}
		if (RESULT_ERROR == result) {
			Reset();  // Don't allow further DecodeChunk calls
			return false;
		}
		unparsed_bytes_ = IoBuffer.allocate(parseable_chunk.remaining() + 128);
		unparsed_bytes_.put(parseable_chunk);
		AppendNewOutputText(output_string);
		return true;
	}

	public boolean FinishDecoding() {
		boolean success = true;
		if (!start_decoding_was_called_) {
			LOGGER.warn("FinishDecoding() called before StartDecoding(), or called after DecodeChunk() returned false");
			success = false;
		} else if (!IsDecodingComplete()) {
			LOGGER.error("FinishDecoding() called before parsing entire delta file window");
			success = false;
		}
		// Reset the object state for the next decode operation
		Reset();
		return success;
	}

	// If true, the version of VCDIFF used in the current delta file allows
	// for the interleaved format, in which instructions, addresses and data
	// are all sent interleaved in the instructions section of each window
	// rather than being sent in separate sections.  This is not part of
	// the VCDIFF draft standard, so we've defined a special version code
	// 'S' which implies that this feature is available.  Even if interleaving
	// is supported, it is not mandatory; interleaved format will be implied
	// if the address and data sections are both zero-length.
	//
	public boolean AllowInterleaved() { return vcdiff_version_code_ == 'S'; }

	// If true, the version of VCDIFF used in the current delta file allows
	// each delta window to contain an Adler32 checksum of the target window data.
	// If the bit 0x08 (VCD_CHECKSUM) is set in the Win_Indicator flags, then
	// this checksum will appear as a variable-length integer, just after the
	// "length of addresses for COPYs" value and before the window data sections.
	// It is possible for some windows in a delta file to use the checksum feature
	// and for others not to use it (and leave the flag bit set to 0.)
	// Just as with AllowInterleaved(), this extension is not part of the draft
	// standard and is only available when the version code 'S' is specified.
	public boolean AllowChecksum() { return vcdiff_version_code_ == 'S'; }

	public boolean SetMaximumTargetFileSize(int new_maximum_target_file_size) {
		maximum_target_file_size_ = new_maximum_target_file_size;
		return true;
	}

	public boolean SetMaximumTargetWindowSize(int new_maximum_target_window_size) {
		if (new_maximum_target_window_size > kTargetSizeLimit) {
			LOGGER.error("Specified maximum target window size {} exceeds limit of {} bytes",
					new_maximum_target_window_size, kTargetSizeLimit);
			return false;
		}
		maximum_target_window_size_ = new_maximum_target_window_size;
		return true;
	}

	// See description of planned_target_file_size_, below.
	public boolean HasPlannedTargetFileSize() {
		return planned_target_file_size_ != kUnlimitedBytes;
	}

	public void SetPlannedTargetFileSize(int planned_target_file_size) {
		planned_target_file_size_ = planned_target_file_size;
	}

	public void AddToTotalTargetWindowSize(int window_size) {
		total_of_target_window_sizes_ += window_size;
	}

	// Checks to see whether the decoded target data has reached its planned size.
	public boolean ReachedPlannedTargetFileSize() {
		if (!HasPlannedTargetFileSize()) {
			return false;
		}
		// The planned target file size should not have been exceeded.
		// TargetWindowWouldExceedSizeLimits() ensures that the advertised size of
		// each target window would not make the target file exceed that limit, and
		// DecodeBody() will return RESULT_ERROR if the actual decoded output ever
		// exceeds the advertised target window size.
		if (total_of_target_window_sizes_ > planned_target_file_size_) {
			LOGGER.error("Internal error: Decoded data size {} exceeds planned target file size {}",
					total_of_target_window_sizes_, planned_target_file_size_);
			return true;
		}
		return total_of_target_window_sizes_ == planned_target_file_size_;
	}

	// Checks to see whether adding a new target window of the specified size
	// would exceed the planned target file size, the maximum target file size,
	// or the maximum target window size.  If so, logs an error and returns true;
	// otherwise, returns false.
	public boolean TargetWindowWouldExceedSizeLimits(int window_size) {
		if (window_size > maximum_target_window_size_) {
			LOGGER.error("Length of target window ({}) exceeds limit of {} bytes", window_size, maximum_target_window_size_);
			return true;
		}
		if (HasPlannedTargetFileSize()) {
			// The logical expression to check would be:
			//
			//   total_of_target_window_sizes_ + window_size > planned_target_file_size_
			//
			// but the addition might cause an integer overflow if target_bytes_to_add
			// is very large.  So it is better to check target_bytes_to_add against
			// the remaining planned target bytes.
			int remaining_planned_target_file_size =
				planned_target_file_size_ - total_of_target_window_sizes_;
			if (window_size > remaining_planned_target_file_size) {
				LOGGER.error("Length of target window ({} bytes) plus previous windows ({} bytes) would exceed planned size of {} bytes",
						new Object[] { window_size, total_of_target_window_sizes_, planned_target_file_size_ });
				return true;
			}
		}
		int remaining_maximum_target_bytes = maximum_target_file_size_ - total_of_target_window_sizes_;
		if (window_size > remaining_maximum_target_bytes) {
			LOGGER.error("Length of target window ({} bytes) plus previous windows ({} bytes) would exceed maximum target file size of {} bytes",
					new Object[] { window_size, total_of_target_window_sizes_, maximum_target_file_size_} );
			return true;
		}
		return false;
	}

	// Returns the amount of input data passed to the last DecodeChunk()
	// that was not consumed by the decoder.  This is essential if
	// SetPlannedTargetFileSize() is being used, in order to preserve the
	// remaining input data stream once the planned target file has been decoded.
	public int GetUnconsumedDataSize() {
		// FIXME
		return unparsed_bytes_.remaining();
	}

	// This function will return true if the decoder has parsed a complete delta
	// file header plus zero or more delta file windows, with no data left over.
	// It will also return true if no delta data at all was decoded.  If these
	// conditions are not met, then FinishDecoding() should not be called.
	public boolean IsDecodingComplete() {
		if (!FoundFileHeader()) {
			// No complete delta file header has been parsed yet.  DecodeChunk()
			// may have received some data that it hasn't yet parsed, in which case
			// decoding is incomplete.
			// FIXME
			return unparsed_bytes_.remaining() == 0;
		} else if (custom_code_table_decoder_ != null) {
			// The decoder is in the middle of parsing a custom code table.
			return false;
		} else if (delta_window_.FoundWindowHeader()) {
			// The decoder is in the middle of parsing an interleaved format delta
			// window.
			return false;
		} else if (ReachedPlannedTargetFileSize()) {
			// The decoder found exactly the planned number of bytes.  In this case
			// it is OK for unparsed_bytes_ to be non-empty; it contains the leftover
			// data after the end of the delta file.
			return true;
		} else {
			// No complete delta file window has been parsed yet.  DecodeChunk()
			// may have received some data that it hasn't yet parsed, in which case
			// decoding is incomplete.
			// FIXME
			return unparsed_bytes_.remaining() == 0;
		}
	}

	public byte[] dictionary_ptr() { return dictionary_ptr_; }

	public int dictionary_size() { return dictionary_ptr_.length; }

	public VCDiffAddressCache addr_cache() { return addr_cache_; }

	IoBuffer decoded_target() { return decoded_target_; }

	public boolean allow_vcd_target() { return allow_vcd_target_; }

	public void SetAllowVcdTarget(boolean allow_vcd_target) {
		if (start_decoding_was_called_) {
			LOGGER.error("SetAllowVcdTarget() called after StartDecoding()");
			return;
		}
		allow_vcd_target_ = allow_vcd_target;
	}

	// Reads the VCDiff delta file header section as described in RFC section 4.1,
	// except the custom code table data.  Returns RESULT_ERROR if an error
	// occurred, or RESULT_END_OF_DATA if the end of available data was reached
	// before the entire header could be read.  (The latter may be an error
	// condition if there is no more data available.)  Otherwise, advances
	// data->position_ past the header and returns RESULT_SUCCESS.

	// Reads the VCDiff delta file header section as described in RFC section 4.1:
	//
	//	     Header1                                  - byte = 0xD6 (ASCII 'V' | 0x80)
	//	     Header2                                  - byte = 0xC3 (ASCII 'C' | 0x80)
	//	     Header3                                  - byte = 0xC4 (ASCII 'D' | 0x80)
	//	     Header4                                  - byte
	//	     Hdr_Indicator                            - byte
	//	     [Secondary compressor ID]                - byte
	//	     [Length of code table data]              - integer
	//	     [Code table data]
	//
	// Initializes the code table and address cache objects.  Returns RESULT_ERROR
	// if an error occurred, and RESULT_END_OF_DATA if the end of available data was
	// reached before the entire header could be read.  (The latter may be an error
	// condition if there is no more data available.)  Otherwise, returns
	// RESULT_SUCCESS, and removes the header bytes from the data string.
	//
	// It's relatively inefficient to expect this function to parse any number of
	// input bytes available, down to 1 byte, but it is necessary in case the input
	// is not a properly formatted VCDIFF delta file.  If the entire input consists
	// of two bytes "12", then we should recognize that it does not match the
	// initial VCDIFF magic number "VCD" and report an error, rather than waiting
	// indefinitely for more input that will never arrive.
	private int ReadDeltaFileHeader(IoBuffer data) {
		if (FoundFileHeader()) {
			return RESULT_SUCCESS;
		}
		int data_size = data.remaining();
		final DeltaFileHeader header = new DeltaFileHeader(data.slice());
			boolean wrong_magic_number = false;
			switch (data_size) {
			// Verify only the bytes that are available.
			default:
				// Found header contents up to and including VCDIFF version
				vcdiff_version_code_ = header.header4;
				if ((vcdiff_version_code_ != 0x00) &&  // Draft standard VCDIFF (RFC 3284)
						(vcdiff_version_code_ != 'S')) {   // Enhancements for SDCH protocol
					LOGGER.error("Unrecognized VCDIFF format version");
					return RESULT_ERROR;
				}
				// fall through
			case 3:
				if (header.header3 != 0xC4) {  // magic value 'D' | 0x80
					wrong_magic_number = true;
				}
				// fall through
			case 2:
				if (header.header2 != 0xC3) {  // magic value 'C' | 0x80
					wrong_magic_number = true;
				}
				// fall through
			case 1:
				if (header.header1 != 0xD6) {  // magic value 'V' | 0x80
					wrong_magic_number = true;
				}
				// fall through
			case 0:
				if (wrong_magic_number) {
					LOGGER.error("Did not find VCDIFF header bytes; input is not a VCDIFF delta file");
					return RESULT_ERROR;
				}
				if (data_size < DeltaFileHeader.SERIALIZED_SIZE) return RESULT_END_OF_DATA;
			}
			// Secondary compressor not supported.
			if (header.hdr_indicator & VCD_DECOMPRESS) {
				LOGGER.error("Secondary compression is not supported");
				return RESULT_ERROR;
			}
			if (header.hdr_indicator & VCD_CODETABLE) {
				int bytes_parsed = InitCustomCodeTable(data.array(), data.arrayOffset() + data.position() + DeltaFileHeader.SERIALIZED_SIZE,
						data.remaining() - DeltaFileHeader.SERIALIZED_SIZE);
				switch (bytes_parsed) {
				case RESULT_ERROR:
					return RESULT_ERROR;
				case RESULT_END_OF_DATA:
					return RESULT_END_OF_DATA;
				default:
					data.position(data.position() + DeltaFileHeader.SERIALIZED_SIZE + bytes_parsed);
				}
			} else {
				addr_cache_ = new VCDiffAddressCacheImpl();
				// addr_cache_->Init() will be called
				// from VCDiffStreamingDecoderImpl::DecodeChunk()
				data.position(data.position() + DeltaFileHeader.SERIALIZED_SIZE);
			}
			return RESULT_SUCCESS;
	}

	// Indicates whether or not the header has already been read.
	private boolean FoundFileHeader() { return addr_cache_ != null; }

	// If ReadDeltaFileHeader() finds the VCD_CODETABLE flag set within the delta
	// file header, this function parses the custom cache sizes and initializes
	// a nested VCDiffStreamingDecoderImpl object that will be used to parse the
	// custom code table in ReadCustomCodeTable().  Returns RESULT_ERROR if an
	// error occurred, or RESULT_END_OF_DATA if the end of available data was
	// reached before the custom cache sizes could be read.  Otherwise, returns
	// the number of bytes read.
	//
	private int InitCustomCodeTable(byte[] data_start, int offset, int length) {
		// A custom code table is being specified.  Parse the variable-length
		// cache sizes and begin parsing the encoded custom code table.
		Integer near_cache_size = null;
		Integer same_cache_size = null;

		VCDiffHeaderParser header_parser = new VCDiffHeaderParser(ByteBuffer.wrap(data_start, offset, length));
		if ((near_cache_size = header_parser.ParseInt32()) == null) {
			LOGGER.warn("Failed to parse size of near cache");
			return header_parser.GetResult();
		}
		if ((same_cache_size = header_parser.ParseInt32()) == null) {
			LOGGER.warn("Failed to parse size of same cache");
			return header_parser.GetResult();
		}

		custom_code_table_ = new VCDiffCodeTableData();

		// FIXME
		custom_code_table_string_.clear();
		addr_cache_ = new VCDiffAddressCacheImpl(near_cache_size.shortValue(), same_cache_size.shortValue());

		// addr_cache_->Init() will be called
		// from VCDiffStreamingDecoderImpl::DecodeChunk()

		// If we reach this point (the start of the custom code table)
		// without encountering a RESULT_END_OF_DATA condition, then we won't call
		// ReadDeltaFileHeader() again for this delta file.
		//
		// Instantiate a recursive decoder to interpret the custom code table
		// as a VCDIFF encoding of the default code table.
		custom_code_table_decoder_ = new VCDiffStreamingDecoderImpl();

		byte[] codeTableBytes = VCDiffCodeTableData.kDefaultCodeTableData.getBytes();
		custom_code_table_decoder_.StartDecoding(codeTableBytes);
		custom_code_table_decoder_.SetPlannedTargetFileSize(codeTableBytes.length);

		return header_parser.ParsedSize();
	}

	// If a custom code table was specified in the header section that was parsed
	// by ReadDeltaFileHeader(), this function makes a recursive call to another
	// VCDiffStreamingDecoderImpl object (custom_code_table_decoder_), since the
	// custom code table is expected to be supplied as an embedded VCDIFF
	// encoding that uses the standard code table.  Returns RESULT_ERROR if an
	// error occurs, or RESULT_END_OF_DATA if the end of available data was
	// reached before the entire custom code table could be read.  Otherwise,
	// returns RESULT_SUCCESS and sets *data_ptr to the position after the encoded
	// custom code table.  If the function returns RESULT_SUCCESS or
	// RESULT_END_OF_DATA, it advances data->position_ past the parsed bytes.
	//
	private int ReadCustomCodeTable(IoBuffer data) {
		if (custom_code_table_decoder_ == null) {
			return RESULT_SUCCESS;
		}
		if (custom_code_table_ != null) {
			LOGGER.error("Internal error:  custom_code_table_decoder_ is set, but custom_code_table_ is NULL");
			return RESULT_ERROR;
		}
		OutputString<string> output_string(&custom_code_table_string_);
		if (!custom_code_table_decoder_.DecodeChunk(data.UnparsedData(),
				data.UnparsedSize(),
				&output_string)) {
			return RESULT_ERROR;
		}
		if (custom_code_table_string_.length() < VCDiffCodeTableData.SERIALIZED_BYTE_SIZE) {
			// Skip over the consumed data.
			data.Finish();
			return RESULT_END_OF_DATA;
		}
		if (!custom_code_table_decoder_.FinishDecoding()) {
			return RESULT_ERROR;
		}
		if (custom_code_table_string_.length() != VCDiffCodeTableData.SERIALIZED_BYTE_SIZE) {
			LOGGER.error("Decoded custom code table size ({}) does not match size of a code table ({})",
					custom_code_table_string_.length(), VCDiffCodeTableData.SERIALIZED_BYTE_SIZE);
			return RESULT_ERROR;
		}
		
		custom_code_table_ = new VCDiffCodeTableData(custom_code_table_string_.data());

		// FIXME
		custom_code_table_string_.clear();
		// Skip over the consumed data.
		data.position(data.limit() - custom_code_table_decoder_.GetUnconsumedDataSize());
		custom_code_table_decoder_ = null;
		delta_window_.UseCodeTable(custom_code_table_, addr_cache_.LastMode());
		return RESULT_SUCCESS;
	}

	// Called after the decoder exhausts all input data.  This function
	// copies from decoded_target_ into output_string all the data that
	// has not yet been output.  It sets decoded_target_output_position_
	// to mark the start of the next data that needs to be output.
	private void AppendNewOutputText(OutputStream output_string) {
		final int bytes_decoded_this_chunk =
			decoded_target_.size() - decoded_target_output_position_;
		if (bytes_decoded_this_chunk > 0) {
			int target_bytes_remaining = delta_window_.TargetBytesRemaining();
			if (target_bytes_remaining > 0) {
				// The decoder is midway through decoding a target window.  Resize
				// output_string to match the expected length.  The interface guarantees
				// not to resize output_string more than once per target window decoded.
				// FIXME:
				// output_string.ReserveAdditionalBytes(bytes_decoded_this_chunk + target_bytes_remaining);
			}
			output_string.write(
					decoded_target_.array(), decoded_target_.arrayOffset() + decoded_target_output_position_,
					bytes_decoded_this_chunk);
			// TODO Limit or Pos
			decoded_target_output_position_ = decoded_target_.limit();
		}
	}

	// Appends to output_string the portion of decoded_target_ that has
	// not yet been output, then clears decoded_target_.  This function is
	// called after each complete target window has been decoded if
	// allow_vcd_target is false.  In that case, there is no need to retain
	// target data from any window except the current window.
	private void FlushDecodedTarget(OutputStream output_string) throws IOException {
		decoded_target_.flip();
		output_string.write(decoded_target_.array(), decoded_target_.arrayOffset() + decoded_target_.position(), decoded_target_.remaining());
		decoded_target_.clear();
		delta_window_.set_target_window_start_pos(0);
		decoded_target_output_position_ = 0;
	}
}
