package com.googlecode.jvcdiff.codec;

import com.googlecode.jvcdiff.VarInt;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.googlecode.jvcdiff.VCDiffCodeTableWriter.VCD_CHECKSUM;
import static org.junit.Assert.*;

// These are the same tests as for VCDiffInterleavedDecoderTest, with the added
// complication that instead of calling DecodeChunk() once with the entire data
// set, DecodeChunk() is called once for each byte of input.  This is intended
// to shake out any bugs with rewind and resume while parsing chunked data.

public class VCDiffInterleavedDecoderTestByteByByte extends VCDiffDecoderTest {
    
    // Test headers, valid and invalid.

    @Test
    public void DecodeHeaderOnly() throws Exception {
        decoder_.StartDecoding(dictionary_);
        for (int i = 0; i < delta_file_header_.length; ++i) {
            assertTrue(decoder_.DecodeChunk(delta_file_header_, i, 1, output_));
        }
        assertTrue(decoder_.FinishDecoding());
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void PartialHeaderNotEnough() throws Exception {
        delta_file_ = Arrays.copyOf(delta_file_, delta_file_header_.length - 2);
        decoder_.StartDecoding(dictionary_);
        for (int i = 0; i < delta_file_.length; ++i) {
            assertTrue(decoder_.DecodeChunk(delta_file_, i, 1, output_));
        }
        assertFalse(decoder_.FinishDecoding());
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void BadMagicNumber() throws Exception {
        delta_file_[1] = (byte) ('Q' | 0x80);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                // It should fail at the position that was altered
                assertEquals(1, i);
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void BadVersionNumber() throws Exception {
        delta_file_[3] = 0x01;
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(3, i);
                break;
            }
        }
        assertTrue(failed);
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void SecondaryCompressionNotSupported() throws Exception {
        delta_file_[4] = 0x01;
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(4, i);
                break;
            }
        }
        assertTrue(failed);
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void Decode() throws Exception {
        decoder_.StartDecoding(dictionary_);
        for (int i = 0; i < delta_file_.length; ++i) {
            assertTrue(decoder_.DecodeChunk(delta_file_, i, 1, output_));
        }
        assertTrue(decoder_.FinishDecoding());
        assertArrayEquals(expected_target_, output_.toByteArray());
    }

    @Test
    public void DecodeWithChecksum() throws Exception {
        ComputeAndAddChecksum();
        InitializeDeltaFile();
        decoder_.StartDecoding(dictionary_);
        for (int i = 0; i < delta_file_.length; ++i) {
            assertTrue(decoder_.DecodeChunk(delta_file_, i, 1, output_));
        }
        assertTrue(decoder_.FinishDecoding());
        assertArrayEquals(expected_target_, output_.toByteArray());
    }

    @Test
    public void ChecksumDoesNotMatch() throws Exception {
        AddChecksum(0xBADBAD);
        InitializeDeltaFile();
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail after decoding the entire delta file
                assertEquals(delta_file_.length - 1, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void ChecksumIsInvalid64BitVarint() throws Exception {
        final byte[] kInvalidVarint = {
                (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00
        };
        delta_window_header_[0] |= VCD_CHECKSUM;
        delta_window_header_ = ArraysExtra.concat(delta_window_header_, kInvalidVarint);
        // Adjust delta window size to include size of invalid Varint.

        byte[] size_of_invalid_varint = new byte[delta_window_header_[4] + kInvalidVarint.length];
        VarInt.putInt(ByteBuffer.wrap(size_of_invalid_varint), delta_window_header_[4] + kInvalidVarint.length);

        // Is this right?
        delta_window_header_ = ArraysExtra.replace(delta_window_header_, 4, 1, size_of_invalid_varint);
        InitializeDeltaFile();
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail while trying to interpret the checksum.
                assertEquals(delta_file_header_.length + delta_window_header_.length - 2, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void TargetMatchesWindowSizeLimit() throws Exception {
        decoder_.SetMaximumTargetWindowSize(expected_target_.length);
        decoder_.StartDecoding(dictionary_);
        for (int i = 0; i < delta_file_.length; ++i) {
            assertTrue(decoder_.DecodeChunk(delta_file_, i, 1, output_));
        }
        assertTrue(decoder_.FinishDecoding());
        assertArrayEquals(expected_target_, output_.toByteArray());
    }

    @Test
    public void TargetMatchesFileSizeLimit() throws Exception {
        decoder_.SetMaximumTargetFileSize(expected_target_.length);
        decoder_.StartDecoding(dictionary_);
        for (int i = 0; i < delta_file_.length; ++i) {
            assertTrue(decoder_.DecodeChunk(delta_file_, i, 1, output_));
        }
        assertTrue(decoder_.FinishDecoding());
        assertArrayEquals(expected_target_, output_.toByteArray());
    }

    @Test
    public void TargetExceedsWindowSizeLimit() throws Exception {
        decoder_.SetMaximumTargetWindowSize(expected_target_.length - 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void TargetExceedsFileSizeLimit() throws Exception {
        decoder_.SetMaximumTargetFileSize(expected_target_.length - 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    // Fuzz bits to make sure decoder does not violently crash.
    // This test has no expected behavior except that no crashes should occur.
    // In some cases, changing bits will still decode to the correct target;
    // for example, changing unused bits within a bitfield.
    @Test
    public void FuzzBits() throws Exception {
        while (FuzzOneByteInDeltaFile()) {
            decoder_.StartDecoding(dictionary_);
            boolean failed = false;
            for (int i = 0; i < delta_file_.length; ++i) {
                if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                    failed = true;
                    break;
                }
            }
            if (!failed) {
                decoder_.FinishDecoding();
            }
            InitializeDeltaFile();
            output_.reset();
        }
    }

    // If a checksum is present, then fuzzing any of the bits may produce an error,
    // but it should not result in an incorrect target being produced without
    // an error.
    @Test
    public void FuzzBitsWithChecksum() throws Exception {
        ComputeAndAddChecksum();
        InitializeDeltaFile();
        while (FuzzOneByteInDeltaFile()) {
            decoder_.StartDecoding(dictionary_);
            boolean failed = false;
            for (int i = 0; i < delta_file_.length; ++i) {
                if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                    failed = true;
                    break;
                }
            }
            if (!failed) {
                if (decoder_.FinishDecoding()) {
                    // Decoding succeeded.  Make sure the correct target was produced.
                    assertArrayEquals(expected_target_, output_.toByteArray());
                }
            }
            // The decoder should not create more target bytes than were expected.
            assertTrue(expected_target_.length >= output_.size());
            InitializeDeltaFile();
            output_.reset();
        }
    }

    @Test
    public void CopyInstructionsShouldFailIfNoSourceSegment() throws Exception {
        // Replace the Win_Indicator and the source size and source offset with a
        // single 0 byte (a Win_Indicator for a window with no source segment.)
        delta_window_header_ = ArraysExtra.replace(delta_window_header_, 0, 4, new byte[1]);
        InitializeDeltaFile();
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // The first COPY instruction should fail.
                assertEquals(delta_file_header_.length + delta_window_header_.length + 2, i);
                break;
            }
        }
        assertTrue(failed);
        assertArrayEquals(new byte[0], output_.toByteArray());
    }

    @Test
    public void CopyMoreThanExpectedTarget() throws Exception {
        delta_file_[delta_file_header_.length + 0x0C] =
                FirstByteOfStringLength(kExpectedTarget);
        delta_file_[delta_file_header_.length + 0x0D] =
                (byte) (SecondByteOfStringLength(kExpectedTarget) + 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x0D, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    // A COPY instruction with an explicit size of 0 is not illegal according to the
    // standard, although it is inefficient and should not be generated by any
    // reasonable encoder.  Changing the size of a COPY instruction to zero will
    // cause a failure because the generated target window size will not match the
    // expected target size.
    @Test
    public void CopySizeZero() throws Exception {
        delta_file_[delta_file_header_.length + 0x0C] = 0;
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopySizeTooLargeByOne() throws Exception {
        ++delta_file_[delta_file_header_.length + 0x0C];
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopySizeTooSmallByOne() throws Exception {
        --delta_file_[delta_file_header_.length + 0x0C];
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopySizeMaxInt() throws Exception {
        WriteMaxVarintAtOffset(0x0C, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x10, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopySizeNegative() throws Exception {
        WriteNegativeVarintAtOffset(0x0C, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x0F, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopySizeInvalid() throws Exception {
        WriteInvalidVarintAtOffset(0x0C, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x10, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopyAddressBeyondHereAddress() throws Exception {
        delta_file_[delta_file_header_.length + 0x0D] =
                FirstByteOfStringLength(kDictionary);
        delta_file_[delta_file_header_.length + 0x0E] =
                SecondByteOfStringLength(kDictionary);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x0E, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopyAddressMaxInt() throws Exception {
        WriteMaxVarintAtOffset(0x0D, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x11, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopyAddressNegative() throws Exception {
        WriteNegativeVarintAtOffset(0x0D, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x10, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void CopyAddressInvalid() throws Exception {
        WriteInvalidVarintAtOffset(0x0D, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x11, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void AddMoreThanExpectedTarget() throws Exception {
        delta_file_[delta_file_header_.length + 0x0F] =
                FirstByteOfStringLength(kExpectedTarget);
        delta_file_[delta_file_header_.length + 0x10] =
                (byte) (SecondByteOfStringLength(kExpectedTarget) + 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x10, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    // An ADD instruction with an explicit size of 0 is not illegal according to the
    // standard, although it is inefficient and should not be generated by any
    // reasonable encoder.  Changing the size of an ADD instruction to zero will
    // cause a failure because the generated target window size will not match the
    // expected target size.
    @Test
    public void AddSizeZero() throws Exception {
        delta_file_[delta_file_header_.length + 0x0F] = 0;
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void AddSizeTooLargeByOne() throws Exception {
        ++delta_file_[delta_file_header_.length + 0x0F];
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void AddSizeTooSmallByOne() throws Exception {
        --delta_file_[delta_file_header_.length + 0x0F];
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void AddSizeMaxInt() throws Exception {
        WriteMaxVarintAtOffset(0x0F, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x13, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void AddSizeNegative() throws Exception {
        WriteNegativeVarintAtOffset(0x0F, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x12, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void AddSizeInvalid() throws Exception {
        WriteInvalidVarintAtOffset(0x0F, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x13, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void RunMoreThanExpectedTarget() throws Exception {
        delta_file_[delta_file_header_.length + 0x5F] =
                FirstByteOfStringLength(kExpectedTarget);
        delta_file_[delta_file_header_.length + 0x60] =
                (byte) (SecondByteOfStringLength(kExpectedTarget) + 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x60, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    // A RUN instruction with an explicit size of 0 is not illegal according to the
    // standard, although it is inefficient and should not be generated by any
    // reasonable encoder.  Changing the size of a RUN instruction to zero will
    // cause a failure because the generated target window size will not match the
    // expected target size.
    @Test
    public void RunSizeZero() throws Exception {
        delta_file_[delta_file_header_.length + 0x5F] = 0;
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void RunSizeTooLargeByOne() throws Exception {
        ++delta_file_[delta_file_header_.length + 0x5F];
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void RunSizeTooSmallByOne() throws Exception {
        --delta_file_[delta_file_header_.length + 0x5F];
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void RunSizeMaxInt() throws Exception {
        WriteMaxVarintAtOffset(0x5F, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x63, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void RunSizeNegative() throws Exception {
        WriteNegativeVarintAtOffset(0x5F, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x62, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

    @Test
    public void RunSizeInvalid() throws Exception {
        WriteInvalidVarintAtOffset(0x5F, 1);
        decoder_.StartDecoding(dictionary_);
        boolean failed = false;
        for (int i = 0; i < delta_file_.length; ++i) {
            if (!decoder_.DecodeChunk(delta_file_, i, 1, output_)) {
                failed = true;
                // It should fail at the position that was altered
                assertEquals(delta_file_header_.length + 0x63, i);
                break;
            }
        }
        assertTrue(failed);
        // The decoder should not create more target bytes than were expected.
        assertTrue(expected_target_.length >= output_.size());
    }

}
