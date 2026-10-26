package com.snail.platform.framework.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 职责:SFTP 端口适配器的纯本地单测——不连外部存储、不需要库,起内嵌 SFTP 即可跑。
 * 上游:StoragePort 契约;下游:SftpStoragePort 与 SftpTestServer。
 * 口径:覆盖适配器自己承诺的四件事——目录逐级自建(base-dir 不存在也一样)、读流与会话同生命周期、
 * 删除幂等、键布局原样落盘(人工排查对得上对象键);同键重写覆盖是端口契约的一部分,一并钉住。
 * 每次操作独立建连是适配器的既定形态,这里靠"同一个端口实例连做多次操作不断"兜住,不测连接池。
 */
class SftpStoragePortTest {

    /** 三级键:验证适配器逐级建目录,而不是一次性建末段 */
    private static final String NESTED_KEY = "7/202610/1c2d3e4f.txt";

    private static final byte[] PAYLOAD = "sftp port payload".getBytes(StandardCharsets.UTF_8);

    private static SftpTestServer server;

    @BeforeAll
    static void startServer() {
        server = new SftpTestServer(Paths.get("./target/sftp-port-test").toAbsolutePath().normalize());
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void putCreatesNestedDirectoriesAndKeyLaysOutAsIs() {
        SftpStoragePort port = server.portHandle();
        port.put(NESTED_KEY, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length, "text/plain");

        assertTrue(port.exists(NESTED_KEY), "写入后存在性探测应为真");
        Path landed = server.home().resolve(NESTED_KEY);
        assertTrue(Files.isRegularFile(landed), "对象键应按原布局逐级落盘: " + landed);
    }

    @Test
    void getReturnsExactBytesAndStreamClosesSession() throws IOException {
        SftpStoragePort port = server.portHandle();
        String key = "7/202610/read-once.txt";
        port.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length, "text/plain");

        byte[] read;
        try (InputStream in = port.get(key)) {
            read = in.readAllBytes();
        }
        assertArrayEquals(PAYLOAD, read, "下载字节应与上传一致");

        // 流关闭后会话必须一起断开:同键再读一次,证明适配器能重新建连
        try (InputStream again = port.get(key)) {
            assertArrayEquals(PAYLOAD, again.readAllBytes(), "二次读取应重新建连成功");
        }
    }

    @Test
    void deleteIsIdempotentAndMissingKeyIsNotPresent() {
        SftpStoragePort port = server.portHandle();
        String key = "7/202610/delete-me.txt";
        port.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length, "text/plain");

        port.delete(key);
        assertFalse(port.exists(key), "删除后存在性探测应为假");
        port.delete(key);
        assertFalse(port.exists(key), "重复删除应保持幂等且不报错");

        assertFalse(port.exists("7/202610/never-written.txt"), "假键存在性探测应为假");
    }

    @Test
    void putOverwritesExistingKey() {
        SftpStoragePort port = server.portHandle();
        String key = "7/202610/overwrite-me.txt";
        byte[] replaced = "replaced".getBytes(StandardCharsets.UTF_8);
        port.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length, "text/plain");
        port.put(key, new ByteArrayInputStream(replaced), replaced.length, "text/plain");

        try (InputStream in = port.get(key)) {
            assertEquals("replaced", new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "同键写入应覆盖旧内容");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void baseDirThatDoesNotExistIsCreatedOnPut() {
        // baseDir 允许不存在:取一个虚拟根下尚未创建的目录段,put 应逐级自建
        SnailStorageProperties.Sftp config = server.properties();
        config.setBaseDir("/cold-start-dir");
        SftpStoragePort port = new SftpStoragePort(config);
        String key = "sub/dir/new.txt";

        port.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length, "text/plain");

        assertTrue(port.exists(key), "冷目录下的写入应自建成功");
        Path landed = server.home().resolve("cold-start-dir").resolve(key);
        assertTrue(Files.isRegularFile(landed), "冷目录应真实创建: " + landed);
    }

    /**
     * 严格指纹校验的失败侧:known_hosts 里是另一把公钥,连接必须在握手阶段被拒绝,
     * 而不是带着错误的主机身份继续传输文件。
     */
    @Test
    void strictHostKeyCheckingRejectsUnknownFingerprint() throws Exception {
        SnailStorageProperties.Sftp config = server.properties();
        config.setStrictHostKeyChecking(true);
        Path knownHosts = server.home().resolve("known_hosts-unrelated");
        KeyPair other = KeyPair.genKeyPair(new JSch(), KeyPair.RSA);
        String entry = "[" + config.getHost() + "]:" + config.getPort() + " "
                + other.getKeyType() + " "
                + Base64.getEncoder().encodeToString(other.getPublicKeyBlob());
        Files.writeString(knownHosts, entry + System.lineSeparator(), StandardCharsets.UTF_8);
        config.setKnownHosts(knownHosts.toString());
        SftpStoragePort port = new SftpStoragePort(config);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> port.put("7/202610/strict.txt", new ByteArrayInputStream(PAYLOAD),
                        PAYLOAD.length, "text/plain"));
        assertTrue(error.getMessage().contains("SFTP 连接失败"),
                "指纹不匹配应在建连阶段失败: " + error.getMessage());
    }

    /**
     * S3 寻址开关的默认值钉在这儿:建 S3Client 不联网,不值得为它单开一个类,
     * 而"默认必须等于 MinIO 形态"是适配器自己的配置契约,漂成 false 会让自建存储静默连错。
     */
    @Test
    void s3PropertiesDefaultToPathStyleAndCanOptOut() {
        SnailStorageProperties.S3 s3 = new SnailStorageProperties.S3();
        assertTrue(s3.isPathStyle(), "path-style 默认必须为真");
        s3.setPathStyle(false);
        assertFalse(s3.isPathStyle(), "显式关掉后应保持虚拟主机寻址");
    }
}
