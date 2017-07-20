/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.remoting.protocol;

import com.alibaba.fastjson.annotation.JSONField;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.remoting.CommandCustomHeader;
import org.apache.rocketmq.remoting.annotation.CFNotNull;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemotingCommand {

    private static final String SERIALIZE_TYPE_PROPERTY = "rocketmq.serialize.type";

    private static final String SERIALIZE_TYPE_ENV = "ROCKETMQ_SERIALIZE_TYPE";

    public static final String REMOTING_VERSION_KEY = "rocketmq.remoting.version";

    private static final int RPC_TYPE = 0; // 0, REQUEST_COMMAND

    private static final int RPC_ONEWAY = 1; // 0, RPC

    private static final Map<Class<? extends CommandCustomHeader>, Field[]> CLASS_HASH_MAP =
        new HashMap<Class<? extends CommandCustomHeader>, Field[]>();

    private static final Map<Class, String> CANONICAL_NAME_CACHE = new HashMap<Class, String>();
    // 1, Oneway
    // 1, RESPONSE_COMMAND
    private static final Map<Field, Annotation> NOT_NULL_ANNOTATION_CACHE = new HashMap<Field, Annotation>();
    private static final String STRING_CANONICAL_NAME = String.class.getCanonicalName();
    private static final String DOUBLE_CANONICAL_NAME_1 = Double.class.getCanonicalName();
    private static final String DOUBLE_CANONICAL_NAME_2 = double.class.getCanonicalName();
    private static final String INTEGER_CANONICAL_NAME_1 = Integer.class.getCanonicalName();
    private static final String INTEGER_CANONICAL_NAME_2 = int.class.getCanonicalName();
    private static final String LONG_CANONICAL_NAME_1 = Long.class.getCanonicalName();
    private static final String LONG_CANONICAL_NAME_2 = long.class.getCanonicalName();
    private static final String BOOLEAN_CANONICAL_NAME_1 = Boolean.class.getCanonicalName();
    private static final String BOOLEAN_CANONICAL_NAME_2 = boolean.class.getCanonicalName();
    private static volatile int configVersion = -1;

    /**
     * 请求ID生成器
     */
    private static AtomicInteger requestId = new AtomicInteger(0);

    /**
     * 初始化序列化类型
     */
    private static SerializeType serializeTypeConfigInThisServer = SerializeType.JSON;
    static {
        final String protocol = System.getProperty(SERIALIZE_TYPE_PROPERTY, System.getenv(SERIALIZE_TYPE_ENV));
        if (!isBlank(protocol)) {
            try {
                serializeTypeConfigInThisServer = SerializeType.valueOf(protocol);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("parser specified protocol error. protocol=" + protocol, e);
            }
        }
    }

    ///// 以下为请求头字段

    /**
     * 请求码
     */
    private int code;

    /**
     * 语言类型
     */
    private LanguageCode language = LanguageCode.JAVA;

    /**
     * 版本号
     */
    private int version = 0;

    /**
     * 请求ID，与ResponseCommand对应
     */
    private int opaque = requestId.getAndIncrement();

    /**
     * 标记
     */
    private int flag = 0;

    /**
     * 备注
     */
    private String remark;

    /**
     * 扩展字段
     */
    private HashMap<String, String> extFields;

    /**
     * 序列化类型
     */
    private SerializeType serializeTypeCurrentRPC = serializeTypeConfigInThisServer;

    /**
     * Body数据
     */
    private transient byte[] body;

    /**
     * 定制Header
     */
    private transient CommandCustomHeader customHeader;

    protected RemotingCommand() {
    }

    public static RemotingCommand createRequestCommand(int code, CommandCustomHeader customHeader) {
        RemotingCommand cmd = new RemotingCommand();
        cmd.setCode(code);
        cmd.customHeader = customHeader;
        setCmdVersion(cmd);
        return cmd;
    }

    private static void setCmdVersion(RemotingCommand cmd) {
        if (configVersion >= 0) {
            cmd.setVersion(configVersion);
        } else {
            String v = System.getProperty(REMOTING_VERSION_KEY);
            if (v != null) {
                int value = Integer.parseInt(v);
                cmd.setVersion(value);
                configVersion = value;
            }
        }
    }

    public static RemotingCommand createResponseCommand(Class<? extends CommandCustomHeader> classHeader) {
        return createResponseCommand(RemotingSysResponseCode.SYSTEM_ERROR, "not set any response code", classHeader);
    }

    public static RemotingCommand createResponseCommand(int code, String remark, Class<? extends CommandCustomHeader> classHeader) {
        RemotingCommand cmd = new RemotingCommand();
        cmd.markResponseType();
        cmd.setCode(code);
        cmd.setRemark(remark);
        setCmdVersion(cmd);

        if (classHeader != null) {
            try {
                CommandCustomHeader objectHeader = classHeader.newInstance();
                cmd.customHeader = objectHeader;
            } catch (InstantiationException e) {
                return null;
            } catch (IllegalAccessException e) {
                return null;
            }
        }

        return cmd;
    }

    public static RemotingCommand createResponseCommand(int code, String remark) {
        return createResponseCommand(code, remark, null);
    }

    public static RemotingCommand decode(final byte[] array) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(array);
        return decode(byteBuffer);
    }

    public static RemotingCommand decode(final ByteBuffer byteBuffer) {

        // 数据总长度
        int length = byteBuffer.limit();

        // 序列化类型字节(1 byte) + header长度(3 byte)
        int oriHeaderLen = byteBuffer.getInt();

        // 获取header长度
        int headerLength = getHeaderLength(oriHeaderLen);

        byte[] headerData = new byte[headerLength];
        // 填充header数据
        byteBuffer.get(headerData);

        // 反序列化header数据，并构建RemotingCommand对象
        RemotingCommand cmd = headerDecode(headerData, getProtocolType(oriHeaderLen));

        // body长度
        int bodyLength = length - 4 - headerLength;
        byte[] bodyData = null;
        if (bodyLength > 0) {
            bodyData = new byte[bodyLength];
            // 填充body数据
            byteBuffer.get(bodyData);
        }
        cmd.body = bodyData;

        return cmd;
    }

    public static int getHeaderLength(int length) {
        return length & 0xFFFFFF;
    }

    private static RemotingCommand headerDecode(byte[] headerData, SerializeType type) {
        switch (type) {
            case JSON:
                RemotingCommand resultJson = RemotingSerializable.decode(headerData, RemotingCommand.class);
                resultJson.setSerializeTypeCurrentRPC(type);
                return resultJson;
            case ROCKETMQ:
                RemotingCommand resultRMQ = RocketMQSerializable.rocketMQProtocolDecode(headerData);
                resultRMQ.setSerializeTypeCurrentRPC(type);
                return resultRMQ;
            default:
                break;
        }

        return null;
    }

    public static SerializeType getProtocolType(int source) {
        return SerializeType.valueOf((byte) ((source >> 24) & 0xFF));
    }

    public static int createNewRequestId() {
        return requestId.incrementAndGet();
    }

    public static SerializeType getSerializeTypeConfigInThisServer() {
        return serializeTypeConfigInThisServer;
    }

    private static boolean isBlank(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static byte[] markProtocolType(int source, SerializeType type) {

        byte[] result = new byte[4];

        // e.g. source = 100 (00000000 00000000 00000000 1100100)

        result[0] = type.getCode();
        result[1] = (byte) ((source >> 16) & 0xFF);
        result[2] = (byte) ((source >> 8) & 0xFF);
        result[3] = (byte) (source & 0xFF);

        // [00000000, 00000000, 00000000, 1100100]
        return result;
    }

    public void markResponseType() {
        int bits = 1 << RPC_TYPE;
        this.flag |= bits;
    }

    public CommandCustomHeader readCustomHeader() {
        return customHeader;
    }

    public void writeCustomHeader(CommandCustomHeader customHeader) {
        this.customHeader = customHeader;
    }

    public CommandCustomHeader decodeCommandCustomHeader(Class<? extends CommandCustomHeader> classHeader) throws RemotingCommandException {
        CommandCustomHeader objectHeader;
        try {
            objectHeader = classHeader.newInstance();
        } catch (InstantiationException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }

        if (this.extFields != null) {

            Field[] fields = getClazzFields(classHeader);
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String fieldName = field.getName();
                    if (!fieldName.startsWith("this")) {
                        try {
                            String value = this.extFields.get(fieldName);
                            if (null == value) {
                                Annotation annotation = getNotNullAnnotation(field);
                                if (annotation != null) {
                                    throw new RemotingCommandException("the custom field <" + fieldName + "> is null");
                                }

                                continue;
                            }

                            field.setAccessible(true);
                            String type = getCanonicalName(field.getType());
                            Object valueParsed;

                            if (type.equals(STRING_CANONICAL_NAME)) {
                                valueParsed = value;
                            } else if (type.equals(INTEGER_CANONICAL_NAME_1) || type.equals(INTEGER_CANONICAL_NAME_2)) {
                                valueParsed = Integer.parseInt(value);
                            } else if (type.equals(LONG_CANONICAL_NAME_1) || type.equals(LONG_CANONICAL_NAME_2)) {
                                valueParsed = Long.parseLong(value);
                            } else if (type.equals(BOOLEAN_CANONICAL_NAME_1) || type.equals(BOOLEAN_CANONICAL_NAME_2)) {
                                valueParsed = Boolean.parseBoolean(value);
                            } else if (type.equals(DOUBLE_CANONICAL_NAME_1) || type.equals(DOUBLE_CANONICAL_NAME_2)) {
                                valueParsed = Double.parseDouble(value);
                            } else {
                                throw new RemotingCommandException("the custom field <" + fieldName + "> type is not supported");
                            }

                            field.set(objectHeader, valueParsed);

                        } catch (Throwable e) {
                        }
                    }
                }
            }

            objectHeader.checkFields();
        }

        return objectHeader;
    }

    private Field[] getClazzFields(Class<? extends CommandCustomHeader> classHeader) {
        Field[] field = CLASS_HASH_MAP.get(classHeader);

        if (field == null) {
            field = classHeader.getDeclaredFields();
            synchronized (CLASS_HASH_MAP) {
                CLASS_HASH_MAP.put(classHeader, field);
            }
        }
        return field;
    }

    private Annotation getNotNullAnnotation(Field field) {
        Annotation annotation = NOT_NULL_ANNOTATION_CACHE.get(field);

        if (annotation == null) {
            annotation = field.getAnnotation(CFNotNull.class);
            synchronized (NOT_NULL_ANNOTATION_CACHE) {
                NOT_NULL_ANNOTATION_CACHE.put(field, annotation);
            }
        }
        return annotation;
    }

    private String getCanonicalName(Class clazz) {
        String name = CANONICAL_NAME_CACHE.get(clazz);

        if (name == null) {
            name = clazz.getCanonicalName();
            synchronized (CANONICAL_NAME_CACHE) {
                CANONICAL_NAME_CACHE.put(clazz, name);
            }
        }
        return name;
    }

    /**
     * 协议格式：
     * <p>
     <ul>
        <li>
            4 bytes：总字节长度 = 4 + header.length + body.length
        </li>
        <li>
            1 byte：序列化类型(0：JSON，1：ROCKETMQ)
        </li>
        <li>
            3 bytes：header字节长度，2的24次方，即header数据最大16M，body数据最大4G - 16M
        </li>
        <li>
            header bytes：header数据
        </li>
        <li>
            body bytes：body数据
        </li>
     </ul>
     * </p>
     */
    public ByteBuffer encode() {

        // 计算整个command需要的字节数
        // header数据长度需要4个字节
        int length = 4;

        // 编码header
        byte[] headerData = this.headerEncode();
        // header真实数据字节数
        length += headerData.length;

        // 3> body真实数据字节数
        if (this.body != null) {
            length += body.length;
        }

        // 申请缓冲区，加上的4个字节用于存放数据总长度
        ByteBuffer result = ByteBuffer.allocate(4 + length);

        // 放入数据总长度
        result.putInt(length);

        // header length = 序列化类型(1 byte) + header数据长度(3 byte)
        // 注意，这里存放header长度的4个字节中，第1个字节存放了序列化类型，剩下3个字节存放header的数据长度，
        // 即header数据长度最大为2^24，即16M
        result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

        // 放入header数据
        result.put(headerData);

        // 放入body数据
        if (this.body != null) {
            result.put(this.body);
        }

        // 将ByteBuffer切换到读模式
        result.flip();

        return result;
    }

    private byte[] headerEncode() {

        // 将请求命令自定义的字段放入extFields
        this.makeCustomHeaderToNet();

        if (SerializeType.ROCKETMQ == serializeTypeCurrentRPC) {
            // ROCKETMQ序列化
            return RocketMQSerializable.rocketMQProtocolEncode(this);
        } else {
            // JSON序列化
            return RemotingSerializable.encode(this);
        }
    }

    /**
     * 将customHeader对象的各字段放入extFields，随header传输
     */
    public void makeCustomHeaderToNet() {

        if (this.customHeader != null) {
            // 反射获取请求命令对象自定义的字段
            Field[] fields = getClazzFields(customHeader.getClass());
            if (null == this.extFields) {
                this.extFields = new HashMap<String, String>();
            }

            for (Field field : fields) {
                // 取非static的字段
                if (!Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName();
                    if (!name.startsWith("this")) {
                        // 非this开头的字段
                        Object value = null;
                        try {
                            field.setAccessible(true);
                            value = field.get(this.customHeader);
                        } catch (IllegalArgumentException e) {
                        } catch (IllegalAccessException e) {
                        }

                        if (value != null) {
                            // 非空，则放入扩展字段
                            this.extFields.put(name, value.toString());
                        }
                    }
                }
            }
        }
    }

    public ByteBuffer encodeHeader() {
        return encodeHeader(this.body != null ? this.body.length : 0);
    }


    public ByteBuffer encodeHeader(final int bodyLength) {
        // 1> header length size
        int length = 4;

        // 2> header data length
        byte[] headerData;
        headerData = this.headerEncode();

        length += headerData.length;

        // 3> body data length
        length += bodyLength;

        ByteBuffer result = ByteBuffer.allocate(4 + length - bodyLength);

        // length
        result.putInt(length);

        // header length
        result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

        // header data
        result.put(headerData);

        result.flip();

        return result;
    }

    public void markOnewayRPC() {
        int bits = 1 << RPC_ONEWAY;
        this.flag |= bits;
    }

    @JSONField(serialize = false)
    public boolean isOnewayRPC() {
        int bits = 1 << RPC_ONEWAY;
        return (this.flag & bits) == bits;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    @JSONField(serialize = false)
    public RemotingCommandType getType() {
        if (this.isResponseType()) {
            return RemotingCommandType.RESPONSE_COMMAND;
        }

        return RemotingCommandType.REQUEST_COMMAND;
    }

    @JSONField(serialize = false)
    public boolean isResponseType() {
        int bits = 1 << RPC_TYPE;
        return (this.flag & bits) == bits;
    }

    public LanguageCode getLanguage() {
        return language;
    }

    public void setLanguage(LanguageCode language) {
        this.language = language;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getOpaque() {
        return opaque;
    }

    public void setOpaque(int opaque) {
        this.opaque = opaque;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public HashMap<String, String> getExtFields() {
        return extFields;
    }

    public void setExtFields(HashMap<String, String> extFields) {
        this.extFields = extFields;
    }

    public void addExtField(String key, String value) {
        if (null == extFields) {
            extFields = new HashMap<String, String>();
        }
        extFields.put(key, value);
    }

    @Override
    public String toString() {
        return "RemotingCommand [code=" + code + ", language=" + language + ", version=" + version + ", opaque=" + opaque + ", flag(B)="
            + Integer.toBinaryString(flag) + ", remark=" + remark + ", extFields=" + extFields + ", serializeTypeCurrentRPC="
            + serializeTypeCurrentRPC + "]";
    }

    public SerializeType getSerializeTypeCurrentRPC() {
        return serializeTypeCurrentRPC;
    }

    public void setSerializeTypeCurrentRPC(SerializeType serializeTypeCurrentRPC) {
        this.serializeTypeCurrentRPC = serializeTypeCurrentRPC;
    }
}