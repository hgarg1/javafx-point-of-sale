package com.example.app.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a JavaFX image as PNG using only java.base. Test-only: keeps screenshot tests free of any
 * extra module requirements in the application's module descriptor.
 */
final class PngWriter {

    private static final byte[] SIGNATURE = {(byte) 137, 'P', 'N', 'G', '\r', '\n', 26, '\n'};

    private PngWriter() {
    }

    static void write(Image image, Path file) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);

        int stride = 1 + width * 4;
        byte[] raw = new byte[height * stride];
        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            raw[rowStart] = 0; // filter type: none
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int o = rowStart + 1 + x * 4;
                raw[o] = (byte) (pixel >>> 16);
                raw[o + 1] = (byte) (pixel >>> 8);
                raw[o + 2] = (byte) pixel;
                raw[o + 3] = (byte) (pixel >>> 24);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ByteBuffer header = ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8)  // bit depth
                .put((byte) 6)  // RGBA
                .put((byte) 0).put((byte) 0).put((byte) 0);
        writeChunk(out, "IHDR", header.array());
        writeChunk(out, "IDAT", deflate(raw));
        writeChunk(out, "IEND", new byte[0]);
        Files.createDirectories(file.getParent());
        Files.write(file, out.toByteArray());
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
