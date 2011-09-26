package com.googlecode.jvcdiff;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class VarInt {

	public static int getInt(ByteBuffer buffer) throws VarIntParseException, VarIntEndOfBufferException {
		final int startPosition = buffer.position();
		int result = 0;

		while (true) {
			if (!buffer.hasRemaining()) {
				throw new VarIntEndOfBufferException();
			}
			if (buffer.position() - startPosition >= 5) {
				throw new VarIntParseException("Data too long for a 32-bit int");
			}

			byte b = buffer.get();
			result += b & 0x7F;

			if ((b & 0x80) == 0) {
				return result;
			}

			result <<= 7;
		}
	}

	public static long getLong(ByteBuffer buffer) throws VarIntParseException, VarIntEndOfBufferException {
		final int startPosition = buffer.position();
		long result = 0;

		while (true) {
			if (!buffer.hasRemaining()) {
				throw new VarIntEndOfBufferException();
			}
			if (buffer.position() - startPosition >= 10) {
				throw new VarIntParseException("Data too long for a 64-bit int");
			}

			byte b = buffer.get();
			result += b & 0x7F;

			if ((b & 0x80) == 0) {
				return result;
			}
			
			result <<= 7;
		}
	}

	public static void putInt(ByteBuffer dest, int val) {
		for (int shift = 28; shift >= 0; shift -= 7) {
			int v2 = val >> shift;
			if (v2 != 0 || shift == 0) {
				byte b = (byte)((v2 & 0x7f) | (shift == 0 ? 0 : 0x80));
				dest.put(b);
			}
		}
	}
	
	public static void writeInt(OutputStream out, int val) throws IOException {
		for (int shift = 28; shift >= 0; shift -= 7) {
			int v2 = val >> shift;
			if (v2 != 0 || shift == 0) {
				byte b = (byte)((v2 & 0x7f) | (shift == 0 ? 0 : 0x80));
				out.write(b);
			}
		}
	}
	
	public static void putLong(ByteBuffer dest, long val) {
		for (int shift = 63; shift >= 0; shift -= 7) {
			long v2 = val >> shift;
			if (v2 != 0 || shift == 0) {
				byte b = (byte)((v2 & 0x7f) | (shift == 0 ? 0 : 0x80));
				dest.put(b);
			}
		}
	}
	
	public static void writeLong(OutputStream out, long val) throws IOException {
		for (int shift = 63; shift >= 0; shift -= 7) {
			long v2 = val >> shift;
			if (v2 != 0 || shift == 0) {
				byte b = (byte)((v2 & 0x7f) | (shift == 0 ? 0 : 0x80));
				out.write(b);
			}
		}
	}
	
	public static int calculateIntLength(int val) {
		int size = 0;
		
		for (int shift = 28; shift >= 0; shift -= 7) {
			int v2 = val >> shift;
			if (v2 != 0 || shift == 0) {
				size++;
			}
		}
		
		return size;
	}
	
	public static int calculateLongLength(long val) {
		int size = 0;
		
		for (int shift = 63; shift >= 0; shift -= 7) {
			long v2 = val >> shift;
			if (v2 != 0 || shift == 0) {
				size++;
			}
		}
		
		return size;
	}

	public static class VarIntParseException extends Exception {
		private static final long serialVersionUID = 2648357489942607161L;

		protected VarIntParseException(String message) {
			super(message);
			if (message == null) {
				throw new NullPointerException();
			}
		}
	}
	
	public static class VarIntEndOfBufferException extends Exception {
		private static final long serialVersionUID = -2989212562402509511L;
		protected VarIntEndOfBufferException() { };
	}
}
