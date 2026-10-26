package com.snail.platform.framework.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * 职责:内嵌 SFTP 测试夹具——随机端口、口令认证、虚拟根指向传入目录,替代外部 SFTP 依赖。
 * 上游:framework-storage 的端口层单测;下游:Apache SSHD 的 SftpSubsystemFactory(仅 test 作用域)。
 * 口径:口令是测试专用值,与 application.yml 的开发默认值同级别,不构成生产凭据;
 * 主机密钥随机自签并落在同一个 home 目录,StrictHostKeyChecking=no 的消费方无需 known_hosts。
 */
public final class SftpTestServer implements AutoCloseable {

    /** 测试登录用户 */
    public static final String USERNAME = "snail";

    /** 测试登录口令 */
    public static final String PASSWORD = "snail";

    /** 已启动的服务端,端口由系统分配 */
    private final SshServer sshd;

    /** 虚拟根目录,对象键在此目录下按原布局落盘 */
    private final Path home;

    public SftpTestServer(Path home) {
        this.home = home.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.home);
            SshServer server = SshServer.setUpDefaultServer();
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(this.home.resolve("hostkey.ser")));
            server.setPasswordAuthenticator((username, password, session) ->
                    USERNAME.equals(username) && PASSWORD.equals(password));
            server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
            server.setFileSystemFactory(new VirtualFileSystemFactory(this.home));
            server.start();
            this.sshd = server;
        } catch (IOException e) {
            throw new UncheckedIOException("内嵌 SFTP 服务器启动失败", e);
        }
    }

    /** 服务端实际监听端口(启动时传 0 由系统分配) */
    public int port() {
        return sshd.getPort();
    }

    /** 虚拟根目录,断言物理落位用 */
    public Path home() {
        return home;
    }

    /** 指向本服务的端口配置;baseDir 取虚拟根 "/"，对象键即根下相对路径 */
    public SnailStorageProperties.Sftp properties() {
        SnailStorageProperties.Sftp sftp = new SnailStorageProperties.Sftp();
        sftp.setHost("127.0.0.1");
        sftp.setPort(port());
        sftp.setUsername(USERNAME);
        sftp.setPassword(PASSWORD);
        sftp.setBaseDir("/");
        return sftp;
    }

    /** 指向本服务的存储端口,等价于 type=sftp 装配出来的 Bean */
    public SftpStoragePort portHandle() {
        return new SftpStoragePort(properties());
    }

    @Override
    public void close() {
        try {
            sshd.stop(true);
        } catch (IOException e) {
            throw new UncheckedIOException("内嵌 SFTP 服务器停止失败", e);
        }
    }
}
