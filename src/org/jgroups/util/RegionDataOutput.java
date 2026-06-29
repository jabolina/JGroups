package org.jgroups.util;

public class RegionDataOutput extends BaseDataOutputStream {

   private byte[] buf;
   private int limit;

   public RegionDataOutput(byte[] buf, int offset, int length) {
      this.buf = buf;
      this.pos = offset;
      this.limit = offset + length;
   }

   public RegionDataOutput reset(byte[] buf, int offset, int length) {
      this.buf = buf;
      this.pos = offset;
      this.limit = offset + length;
      return this;
   }


   @Override
   public void write(int b) {
      ensureCapacity(1);
      buf[pos++] = (byte) b;
   }

   @Override
   public void write(byte[] b, int off, int len) {
      ensureCapacity(len);
      System.arraycopy(b, off, buf, pos, len);
      pos += len;
   }

   @Override
   public void writeInt(int v) {
      ensureCapacity(4);
      Util.INT_ARRAY_VIEW.set(buf, pos, v);
      pos += 4;
   }

   @Override
   public void writeLong(long v) {
      ensureCapacity(8);
      Util.LONG_ARRAY_VIEW.set(buf, pos, v);
      pos += 8;
   }

   @Override
   public void writeShort(int v) {
      ensureCapacity(2);
      buf[pos] = (byte) ((v >> 8) & 0xFF);
      buf[pos + 1] = (byte) (v & 0xFF);
      pos += 2;
   }

   @Override
   protected void ensureCapacity(int bytes) {
      if (pos + bytes > limit)
         throw new IndexOutOfBoundsException("pos=" + pos + ", bytes=" + bytes + ", limit=" + limit);
   }
}
