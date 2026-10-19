package com.jw.snmpdriver.snmp.runtime;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import com.jw.snmpdriver.snmp.address.NumericOid;
import com.jw.snmpdriver.snmp.address.ResolvedAddress;
import com.jw.snmpdriver.snmp.address.SnmpWireType;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.snmp4j.smi.AbstractVariable;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Opaque;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UnsignedInteger32;
import org.snmp4j.smi.Variable;

public final class SnmpValueCodec {
    private static final BigInteger UINT32_MAX = new BigInteger("4294967295");
    private static final BigInteger UINT64_MAX = new BigInteger("18446744073709551615");

    public DataValue toDataValue(ResolvedAddress address, RawBinding binding) {
        try {
            return new DataValue(
                    new Variant(decode(address, binding)),
                    StatusCode.GOOD,
                    new DateTime(binding.receivedAt()),
                    DateTime.now());
        } catch (SnmpValueException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new SnmpValueException(
                    StatusCodes.Bad_DecodingError,
                    "decode",
                    "Failed to decode SNMP binding oid=" + address.oid().canonical()
                            + " as " + address.wireType().canonicalToken(),
                    error
            );
        }
    }

    public Variable toVariable(ResolvedAddress address, Object opcValue) {
        try {
            if (address.stringFallback()) {
                throw new SnmpValueException(
                        StatusCodes.Bad_NotWritable,
                        "write-policy",
                        "Bare OID String fallback is read-only"
                );
            }
            return switch (address.wireType()) {
                case INTEGER32 -> new Integer32(requireSigned32(opcValue));
                case UNSIGNED32 -> new UnsignedInteger32(requireUnsigned32(opcValue));
                case COUNTER32 -> new Counter32(requireUnsigned32(opcValue));
                case GAUGE32 -> new Gauge32(requireUnsigned32(opcValue));
                case TIMETICKS -> new TimeTicks(requireUnsigned32(opcValue));
                case COUNTER64 -> new Counter64(requireUnsigned64(opcValue).longValue());
                case OCTET_STRING -> new OctetString(requireString(opcValue).getBytes(StandardCharsets.UTF_8));
                case BYTE_STRING -> new OctetString(requireBytes(opcValue));
                case OBJECT_IDENTIFIER -> new OID(NumericOid.parse(requireString(opcValue)).canonical());
                case IP_ADDRESS -> new IpAddress(parseIpv4(requireString(opcValue)));
                case OPAQUE -> new Opaque(requireBytes(opcValue));
                case OPAQUE_FLOAT -> new Opaque(encodeOpaqueFloat(requireFloat(opcValue)));
                case OPAQUE_DOUBLE -> new Opaque(encodeOpaqueDouble(requireDouble(opcValue)));
                case STRING_FALLBACK -> throw new SnmpValueException(
                        StatusCodes.Bad_NotWritable,
                        "write-policy",
                        "String fallback is read-only"
                );
            };
        } catch (SnmpValueException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new SnmpValueException(
                    StatusCodes.Bad_EncodingError,
                    "encode",
                    "Failed to encode OPC value as " + address.wireType().canonicalToken()
                            + ": " + error.getMessage(),
                    error
            );
        }
    }

    private Object decode(ResolvedAddress address, RawBinding binding) {
        Variable value = binding.value();
        int syntax = binding.syntax();
        if (isExceptionSyntax(syntax)) {
            throw new SnmpValueException(
                    StatusCodes.Bad_NodeIdUnknown,
                    "protocol",
                    "SNMP agent returned " + AbstractVariable.getSyntaxString(syntax)
                            + " for oid=" + address.oid().canonical()
            );
        }
        if (address.stringFallback()) {
            return value.toString();
        }

        return switch (address.wireType()) {
            case INTEGER32 -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_INTEGER32);
                yield ((Integer32) value).getValue();
            }
            case UNSIGNED32 -> {
                requireOneOf(syntax, SMIConstants.SYNTAX_UNSIGNED_INTEGER32, SMIConstants.SYNTAX_GAUGE32);
                yield Unsigned.uint(((UnsignedInteger32) value).getValue());
            }
            case COUNTER32 -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_COUNTER32);
                yield Unsigned.uint(((Counter32) value).getValue());
            }
            case GAUGE32 -> {
                requireOneOf(syntax, SMIConstants.SYNTAX_GAUGE32, SMIConstants.SYNTAX_UNSIGNED_INTEGER32);
                yield Unsigned.uint(((UnsignedInteger32) value).getValue());
            }
            case TIMETICKS -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_TIMETICKS);
                yield Unsigned.uint(((TimeTicks) value).getValue());
            }
            case COUNTER64 -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_COUNTER64);
                yield Unsigned.ulong(new BigInteger(((Counter64) value).toString()));
            }
            case OCTET_STRING -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_OCTET_STRING);
                yield decodeUtf8(((OctetString) value).getValue());
            }
            case BYTE_STRING -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_OCTET_STRING);
                yield ByteString.of(((OctetString) value).getValue());
            }
            case OBJECT_IDENTIFIER -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_OBJECT_IDENTIFIER);
                yield ((OID) value).toDottedString();
            }
            case IP_ADDRESS -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_IPADDRESS);
                yield ((IpAddress) value).toString();
            }
            case OPAQUE -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_OPAQUE);
                yield ByteString.of(((Opaque) value).getValue());
            }
            case OPAQUE_FLOAT -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_OPAQUE);
                yield decodeOpaqueFloat(((Opaque) value).getValue());
            }
            case OPAQUE_DOUBLE -> {
                requireSyntax(syntax, SMIConstants.SYNTAX_OPAQUE);
                yield decodeOpaqueDouble(((Opaque) value).getValue());
            }
            case STRING_FALLBACK -> value.toString();
        };
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new SnmpValueException(
                    StatusCodes.Bad_DecodingError,
                    "SNMP OctetString is not valid UTF-8",
                    error
            );
        }
    }

    private static int requireSigned32(Object value) {
        BigInteger number = requireIntegral(value);
        if (number.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw outOfRange("Integer32");
        }
        return number.intValue();
    }

    private static long requireUnsigned32(Object value) {
        BigInteger number = requireIntegral(value);
        if (number.signum() < 0 || number.compareTo(UINT32_MAX) > 0) {
            throw outOfRange("Unsigned32");
        }
        return number.longValue();
    }

    private static BigInteger requireUnsigned64(Object value) {
        BigInteger number = requireIntegral(value);
        if (number.signum() < 0 || number.compareTo(UINT64_MAX) > 0) {
            throw outOfRange("Counter64");
        }
        return number;
    }

    private static BigInteger requireIntegral(Object value) {
        if (value instanceof UInteger number) {
            return BigInteger.valueOf(number.longValue());
        }
        if (value instanceof ULong number) {
            return new BigInteger(number.toString());
        }
        if (value instanceof BigInteger number) {
            return number;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        throw typeMismatch("Integral OPC value required");
    }

    private static String requireString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw typeMismatch("String OPC value required");
    }

    private static byte[] requireBytes(Object value) {
        if (value instanceof ByteString bytes) {
            return bytes.bytesOrEmpty();
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        throw typeMismatch("ByteString OPC value required");
    }

    private static float requireFloat(Object value) {
        if (value instanceof Float number) {
            return number;
        }
        throw typeMismatch("Float OPC value required");
    }

    private static double requireDouble(Object value) {
        if (value instanceof Double number) {
            return number;
        }
        throw typeMismatch("Double OPC value required");
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new SnmpValueException(StatusCodes.Bad_SyntaxError, "IpAddress requires IPv4 dotted notation");
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || !parts[i].matches("[0-9]{1,3}")) {
                throw new SnmpValueException(StatusCodes.Bad_SyntaxError, "IpAddress requires IPv4 dotted notation");
            }
            int octet = Integer.parseInt(parts[i]);
            if (octet > 255) {
                throw outOfRange("IpAddress");
            }
            bytes[i] = (byte) octet;
        }
        try {
            InetAddress parsed = InetAddress.getByAddress(bytes);
            if (parsed.getAddress().length != 4) {
                throw new SnmpValueException(StatusCodes.Bad_SyntaxError, "IpAddress requires IPv4");
            }
            return bytes;
        } catch (UnknownHostException impossible) {
            throw new SnmpValueException(StatusCodes.Bad_SyntaxError, "Invalid IPv4 address", impossible);
        }
    }

    private static byte[] encodeOpaqueFloat(float value) {
        ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0x9f).put((byte) 0x78).put((byte) 0x04).putFloat(value);
        return buffer.array();
    }

    private static byte[] encodeOpaqueDouble(double value) {
        ByteBuffer buffer = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0x9f).put((byte) 0x79).put((byte) 0x08).putDouble(value);
        return buffer.array();
    }

    private static float decodeOpaqueFloat(byte[] value) {
        if (value.length != 7
                || value[0] != (byte) 0x9f
                || value[1] != (byte) 0x78
                || value[2] != (byte) 0x04) {
            throw typeMismatch("Opaque value is not RFC 1906 float encoding");
        }
        return ByteBuffer.wrap(value, 3, 4).order(ByteOrder.BIG_ENDIAN).getFloat();
    }

    private static double decodeOpaqueDouble(byte[] value) {
        if (value.length != 11
                || value[0] != (byte) 0x9f
                || value[1] != (byte) 0x79
                || value[2] != (byte) 0x08) {
            throw typeMismatch("Opaque value is not RFC 1906 double encoding");
        }
        return ByteBuffer.wrap(value, 3, 8).order(ByteOrder.BIG_ENDIAN).getDouble();
    }

    private static boolean isExceptionSyntax(int syntax) {
        return syntax == SMIConstants.EXCEPTION_NO_SUCH_OBJECT
                || syntax == SMIConstants.EXCEPTION_NO_SUCH_INSTANCE
                || syntax == SMIConstants.EXCEPTION_END_OF_MIB_VIEW;
    }

    private static void requireSyntax(int actual, int expected) {
        if (actual != expected) {
            throw typeMismatch(
                    "SNMP wire type mismatch expected=" + AbstractVariable.getSyntaxString(expected)
                            + " actual=" + AbstractVariable.getSyntaxString(actual)
            );
        }
    }

    private static void requireOneOf(int actual, int first, int second) {
        if (actual != first && actual != second) {
            throw typeMismatch(
                    "SNMP wire type mismatch expected=" + AbstractVariable.getSyntaxString(first)
                            + "|" + AbstractVariable.getSyntaxString(second)
                            + " actual=" + AbstractVariable.getSyntaxString(actual)
            );
        }
    }

    private static SnmpValueException typeMismatch(String message) {
        return new SnmpValueException(StatusCodes.Bad_TypeMismatch, message);
    }

    private static SnmpValueException outOfRange(String type) {
        return new SnmpValueException(StatusCodes.Bad_OutOfRange, type + " value is outside supported range");
    }
}

