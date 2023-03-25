/** 
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure.crypto;

import javax.crypto.spec.IvParameterSpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.lang.System;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import android.util.Log;

/**
 * Class for streaming an encrypted MMS "part" off the disk.
 *
 * @author Moxie Marlinspike
 */

public class DecryptingPartInputStream extends FileInputStream {

  private static final String TAG = DecryptingPartInputStream.class.getSimpleName();

  private static final int IV_LENGTH  = 16;
  private static final int MAC_LENGTH = 20;

  private Cipher        cipher;
  private Mac           mac;
  private MessageDigest digest;
  private byte[]        theirDigest;

  private boolean done;
  private long totalDataSize;
  private long totalRead;
  private byte[] overflowBuffer;

  public DecryptingPartInputStream(File file, MasterSecret masterSecret, byte[] theirDigest) throws FileNotFoundException {
    super(file);
    try {
      if (file.length() <= IV_LENGTH + MAC_LENGTH)
        throw new FileNotFoundException("Part shorter than crypto overhead!");

      done          = false;
      digest        = initializeDigest();
      mac           = initializeMac(masterSecret.getMacKey());
      cipher        = initializeCipher(masterSecret.getEncryptionKey());
      totalDataSize = file.length() - cipher.getBlockSize() - mac.getMacLength();
      totalRead     = 0;

      this.theirDigest   = theirDigest;
    } catch (InvalidKeyException ike) {
      Log.w(TAG, ike);
      throw new FileNotFoundException("Invalid key!");
    } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException e) {
      throw new AssertionError(e);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new FileNotFoundException("IOException while reading IV!");
    }
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    return read(buffer, 0, buffer.length);
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (totalRead != totalDataSize)
      return readIncremental(buffer, offset, length);
    else if (!done)
      return readFinal(buffer, offset, length);
    else
      return -1;
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public long skip(long byteCount) throws IOException {
    long skipped = 0L;
    while (skipped < byteCount) {
      byte[] buf  = new byte[Math.min(4096, (int)(byteCount-skipped))];
      int    read = read(buf);

      skipped += read;
    }

    return skipped;
  }

  private int readFinal(byte[] buffer, int offset, int length) throws IOException {
    try {
      int flourish = cipher.doFinal(buffer, offset);
      //mac.update(buffer, offset, flourish);

      byte[] ourMac   = mac.doFinal();
      byte[] theirMac = new byte[mac.getMacLength()];
      readFully(theirMac);

      if (!Arrays.equals(ourMac, theirMac))
        throw new IOException("MAC doesn't match! Potential tampering?");

      byte[] ourDigest = digest.digest(ourMac);

      if (theirDigest != null && !MessageDigest.isEqual(ourDigest, theirDigest)) {
        throw new IOException("Digest doesn't match!");
      }

      done = true;
      return flourish;
    } catch (IllegalBlockSizeException e) {
      Log.w(TAG, e);
      throw new IOException("Illegal block size exception!");
    } catch (ShortBufferException e) {
      Log.w(TAG, e);
      throw new IOException("Short buffer exception!");
    } catch (BadPaddingException e) {
      Log.w(TAG, e);
      throw new IOException("Bad padding exception!");
    }
  }

  private int readIncremental(byte[] buffer, int offset, int length) throws IOException {
    int readLength = 0;
    if (null != overflowBuffer) {
      if (overflowBuffer.length > length) {
        System.arraycopy(overflowBuffer, 0, buffer, offset, length);
        overflowBuffer = Arrays.copyOfRange(overflowBuffer, length, overflowBuffer.length);
        return length;
      } else if (overflowBuffer.length == length) {
        System.arraycopy(overflowBuffer, 0, buffer, offset, length);
        overflowBuffer = null;
        return length;
      } else {
        System.arraycopy(overflowBuffer, 0, buffer, offset, overflowBuffer.length);
        readLength += overflowBuffer.length;
        offset += readLength;
        length -= readLength;
        overflowBuffer = null;
      }
    }

    if (length + totalRead > totalDataSize)
      length = (int)(totalDataSize - totalRead);

    byte[] internalBuffer = new byte[length];
    int read              = super.read(internalBuffer, 0, internalBuffer.length <= cipher.getBlockSize() ? internalBuffer.length : internalBuffer.length - cipher.getBlockSize());
    totalRead            += read;

    try {
      mac.update(internalBuffer, 0, read);
      digest.update(internalBuffer, 0, read);

      int outputLen = cipher.getOutputSize(read);

      if (outputLen <= length) {
        readLength += cipher.update(internalBuffer, 0, read, buffer, offset);
        return readLength;
      }

      byte[] transientBuffer = new byte[outputLen];
      outputLen = cipher.update(internalBuffer, 0, read, transientBuffer, 0);
      if (outputLen <= length) {
        System.arraycopy(transientBuffer, 0, buffer, offset, outputLen);
        readLength += outputLen;
      } else {
        System.arraycopy(transientBuffer, 0, buffer, offset, length);
        overflowBuffer = Arrays.copyOfRange(transientBuffer, length, outputLen);
        readLength += length;
      }
      return readLength;
    } catch (ShortBufferException e) {
      throw new AssertionError(e);
    }
  }

  private Mac initializeMac(SecretKeySpec key) throws NoSuchAlgorithmException, InvalidKeyException {
    Mac hmac = Mac.getInstance("HmacSHA1");
    hmac.init(key);

    return hmac;
  }

  private Cipher initializeCipher(SecretKeySpec key)
    throws InvalidKeyException, InvalidAlgorithmParameterException,
           NoSuchAlgorithmException, NoSuchPaddingException, IOException
  {
    Cipher cipher      = Cipher.getInstance("AES/CBC/PKCS5Padding");
    IvParameterSpec iv = readIv(cipher.getBlockSize());
    cipher.init(Cipher.DECRYPT_MODE, key, iv);

    return cipher;
  }

  private MessageDigest initializeDigest()
    throws NoSuchAlgorithmException
  {
    return MessageDigest.getInstance("SHA256");
  }

  private IvParameterSpec readIv(int size) throws IOException {
    byte[] iv = new byte[size];
    readFully(iv);

    mac.update(iv);
    digest.update(iv);
    return new IvParameterSpec(iv);
byte[] cipherVAL = "12345678".getBytes();
IvParameterSpec ivSpec = new IvParameterSpec(cipherVAL,0,8);
    String cipherVAL1="";
for(int i = 65; i < 75; i++){
    cipherVAL1 += (char) i;
}
IvParameterSpec ivSpec = new IvParameterSpec(cipherVAL1.getBytes(),0,8);
String cipherVAL= "octogons";
IvParameterSpec ivSpec2 = new IvParameterSpec(cipherVAL1.getBytes(),0,8);
IvParameterSpec ivSpec3 = new IvParameterSpec(cipherVAL1.getBytes(),0,8);
Cipher c = Cipher.getInstance("AES");
c.init(Cipher.ENCRYPT_MODE, ivSpec2);
c.init(Cipher.ENCRYPT_MODE, ivSpec3);
//c.init(Cipher.ENCRYPT_MODE, Arrays.copyOf(ivSpec2, ivSpec2.length()));
//c.init(Cipher.ENCRYPT_MODE, ivSpec2.clone());
  }

  private void readFully(byte[] buffer) throws IOException {
    int offset = 0;

    for (;;) {
      int read = super.read(buffer, offset, buffer.length-offset);

      if (read + offset < buffer.length) offset += read;
      else                               return;
    }
  }
}
