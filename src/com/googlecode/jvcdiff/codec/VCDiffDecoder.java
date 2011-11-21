package com.googlecode.jvcdiff.codec;

import java.io.OutputStream;


/**
 * A simpler (non-streaming) interface to the VCDIFF decoder that can be used
 * if the entire delta file is available.
 */
public class VCDiffDecoder {
	private VCDiffStreamingDecoder decoder_;

	/**
	 * Replaces old contents of "*target" with the result of decoding
	 * the bytes found in "encoding."
	 *
	 * Returns true if "encoding" was a well-formed sequence of
	 * instructions, and returns false if not.
	 */
	public boolean Decode(byte[] dictionary_ptr, byte[] encoding, int offset, int length, OutputStream target) {
		decoder_.StartDecoding(dictionary_ptr);
		if (!decoder_.DecodeChunk(encoding, offset, length, target)) {
			return false;
		}
		return decoder_.FinishDecoding();
	}
}
