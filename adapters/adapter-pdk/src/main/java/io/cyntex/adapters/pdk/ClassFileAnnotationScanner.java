package io.cyntex.adapters.pdk;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Scans a compiled class's constant pool for an annotation's type descriptor, deciding from the class
 * bytes alone — without loading the class — whether it is worth loading to confirm the annotation.
 * Self-scan uses this to narrow a connector jar's classes to the few that could be the entry class; a
 * real classload and {@code getDeclaredAnnotation} is the authority on a match.
 *
 * <p>Reading only the constant pool keeps the pre-filter cheap and native-friendly: no reflection, no
 * class initialization, and no dependency on the annotation type being on any classpath.
 */
final class ClassFileAnnotationScanner {

    private static final int MAGIC = 0xCAFEBABE;

    // Constant-pool tags (JVMS 4.4). Long and Double each occupy two pool slots; every other kind
    // occupies one. The scanner only reads Utf8 entries and steps over the rest by their fixed width.
    private static final int UTF8 = 1;
    private static final int INTEGER = 3;
    private static final int FLOAT = 4;
    private static final int LONG = 5;
    private static final int DOUBLE = 6;
    private static final int CLASS = 7;
    private static final int STRING = 8;
    private static final int FIELDREF = 9;
    private static final int METHODREF = 10;
    private static final int INTERFACE_METHODREF = 11;
    private static final int NAME_AND_TYPE = 12;
    private static final int METHOD_HANDLE = 15;
    private static final int METHOD_TYPE = 16;
    private static final int DYNAMIC = 17;
    private static final int INVOKE_DYNAMIC = 18;
    private static final int MODULE = 19;
    private static final int PACKAGE = 20;

    private ClassFileAnnotationScanner() {
    }

    /**
     * Whether {@code classBytes} carries {@code annotationDescriptor} — a field descriptor such as
     * {@code Lio/tapdata/pdk/apis/annotations/TapConnectorClass;} — as a constant-pool Utf8 entry, as a
     * class annotated with that type does. Bytes that are not a class file, or are truncated, are
     * treated as no match rather than crashing the scan.
     */
    static boolean bearsAnnotation(byte[] classBytes, String annotationDescriptor) {
        ByteBuffer buf = ByteBuffer.wrap(classBytes); // class files are big-endian, ByteBuffer's default
        byte[] wanted = annotationDescriptor.getBytes(StandardCharsets.UTF_8);
        try {
            if (buf.getInt() != MAGIC) {
                return false;
            }
            buf.getShort(); // minor version
            buf.getShort(); // major version
            int poolCount = buf.getShort() & 0xFFFF;
            for (int index = 1; index < poolCount; index++) {
                int tag = buf.get() & 0xFF;
                switch (tag) {
                    case UTF8 -> {
                        int length = buf.getShort() & 0xFFFF;
                        if (equalsAt(buf, length, wanted)) {
                            return true;
                        }
                        buf.position(buf.position() + length);
                    }
                    case INTEGER, FLOAT, FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE,
                            DYNAMIC, INVOKE_DYNAMIC -> buf.position(buf.position() + 4);
                    case LONG, DOUBLE -> {
                        buf.position(buf.position() + 8);
                        index++; // an eight-byte constant takes two pool slots
                    }
                    case CLASS, STRING, METHOD_TYPE, MODULE, PACKAGE -> buf.position(buf.position() + 2);
                    case METHOD_HANDLE -> buf.position(buf.position() + 3);
                    default -> {
                        return false; // an unknown tag means a pool shape we cannot walk safely
                    }
                }
            }
            return false;
        } catch (BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException truncated) {
            // IndexOutOfBounds covers the absolute reads in equalsAt on an entry the file cuts short.
            return false;
        }
    }

    /** Whether the {@code length} bytes at the buffer's position equal {@code wanted}, leaving the position put. */
    private static boolean equalsAt(ByteBuffer buf, int length, byte[] wanted) {
        if (length != wanted.length) {
            return false;
        }
        int base = buf.position();
        for (int i = 0; i < length; i++) {
            if (buf.get(base + i) != wanted[i]) {
                return false;
            }
        }
        return true;
    }
}
