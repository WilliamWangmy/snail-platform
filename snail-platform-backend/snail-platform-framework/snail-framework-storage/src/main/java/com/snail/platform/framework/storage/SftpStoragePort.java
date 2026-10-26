package com.snail.platform.framework.storage;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 职责:StoragePort 的 SFTP 实现——每次操作独立建连(open session→channel→操作→断开),
 * 不持共享连接,线程安全按最保守形态成立;吞吐不足时再上连接池(路线图项,不进首版)。
 * 上游:StoragePort;下游:JSch 会话与 SFTP 通道。
 * 口径:主机指纹默认严格校验,knownHosts 路径由配置提供;仅开发/测试环境可显式关闭;
 * 建连、开通道与会话读写都有超时,网络故障不会无限挂住请求线程;
 * put 前递归 mkdirs(stat 异常即视为目录缺失,逐级创建),baseDir 允许不存在;
 * 对象路径 = baseDir + "/" + key;get 返回的流挂在本次的会话上,随流关闭一并断开连接。
 */
public class SftpStoragePort implements StoragePort {

    private static final Logger log = LoggerFactory.getLogger(SftpStoragePort.class);

    /** 路径分隔符,SFTP 语义固定正斜杠 */
    private static final String PATH_SEPARATOR = "/";

    /** JSch 通道类型:SFTP 子系统 */
    private static final String CHANNEL_SFTP = "sftp";

    /** 主机指纹校验开关名 */
    private static final String STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking";

    /** 严格校验主机指纹 */
    private static final String HOST_KEY_CHECKING_ON = "yes";

    /** 关闭指纹校验:只给自签指纹的开发/测试环境使用 */
    private static final String HOST_KEY_CHECKING_OFF = "no";

    /** SFTP 错误码:文件或目录不存在 */
    private static final int SSH_FX_NO_SUCH_FILE = 2;

    /** SFTP 错误码:目标已存在(mkdir 并发竞态时容忍) */
    private static final int SSH_FX_FILE_ALREADY_EXISTS = 11;

    /** SFTP 连接配置 */
    private final SnailStorageProperties.Sftp config;

    public SftpStoragePort(SnailStorageProperties.Sftp config) {
        this.config = config;
    }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        // size/contentType 由 SFTP 协议自身语义消化:SFTP 无预声明长度,内容随流写尽
        SessionHolder holder = open();
        try {
            ChannelSftp channel = holder.channel;
            mkdirs(channel, parentOf(remotePath(key)));
            channel.put(in, remotePath(key));
        } catch (SftpException e) {
            throw new IllegalStateException("SFTP 写入失败: " + e.getMessage(), e);
        } finally {
            holder.disconnect();
        }
    }

    @Override
    public InputStream get(String key) {
        SessionHolder holder = open();
        try {
            // SFTP 流由通道驱动,会话必须活到流读完:关闭动作随流一并断开连接
            return new SessionBoundInputStream(holder, holder.channel.get(remotePath(key)));
        } catch (SftpException e) {
            holder.disconnect();
            throw new IllegalStateException("SFTP 读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        SessionHolder holder = open();
        try {
            holder.channel.rm(remotePath(key));
        } catch (SftpException e) {
            if (e.id != SSH_FX_NO_SUCH_FILE) {
                throw new IllegalStateException("SFTP 删除失败: " + e.getMessage(), e);
            }
            // 键不存在视为已删,保持幂等
        } finally {
            holder.disconnect();
        }
    }

    @Override
    public boolean exists(String key) {
        SessionHolder holder = open();
        try {
            holder.channel.stat(remotePath(key));
            return true;
        } catch (SftpException e) {
            if (e.id == SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw new IllegalStateException("SFTP 探测失败: " + e.getMessage(), e);
        } finally {
            holder.disconnect();
        }
    }

    /** 建连并开 SFTP 通道;任何一步失败都把会话拆干净 */
    private SessionHolder open() {
        try {
            JSch jsch = new JSch();
            if (hasText(config.getKnownHosts())) {
                jsch.setKnownHosts(config.getKnownHosts());
            }
            if (hasText(config.getPrivateKey())) {
                jsch.addIdentity(config.getPrivateKey());
            }
            var session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
            if (hasText(config.getPassword())) {
                session.setPassword(config.getPassword());
            }
            session.setConfig(STRICT_HOST_KEY_CHECKING,
                    config.isStrictHostKeyChecking() ? HOST_KEY_CHECKING_ON : HOST_KEY_CHECKING_OFF);
            session.setTimeout(config.getSessionTimeoutMs());
            session.connect(config.getConnectTimeoutMs());
            ChannelSftp channel;
            try {
                channel = (ChannelSftp) session.openChannel(CHANNEL_SFTP);
                channel.connect(config.getConnectTimeoutMs());
            } catch (JSchException e) {
                session.disconnect();
                throw e;
            }
            return new SessionHolder(session, channel);
        } catch (JSchException e) {
            throw new IllegalStateException("SFTP 连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 逐级建目录:stat 异常一律视为不存在(mkdir 再撞"已存在"也容忍),
     * 目录段保持原始相对/绝对形态,兼容 baseDir 为 "/" 或相对路径两种写法。
     */
    private void mkdirs(ChannelSftp channel, String dir) throws SftpException {
        if (dir == null || dir.isBlank()) {
            return;
        }
        StringBuilder level = new StringBuilder();
        for (String segment : dir.split(PATH_SEPARATOR)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (level.isEmpty()) {
                level.append(dir.startsWith(PATH_SEPARATOR) ? PATH_SEPARATOR + segment : segment);
            } else {
                level.append(PATH_SEPARATOR).append(segment);
            }
            String path = level.toString();
            if (existsAsDirectory(channel, path)) {
                continue;
            }
            try {
                channel.mkdir(path);
            } catch (SftpException e) {
                if (e.id != SSH_FX_FILE_ALREADY_EXISTS) {
                    throw e;
                }
            }
        }
    }

    /** stat 异常即视为目录不存在 */
    private boolean existsAsDirectory(ChannelSftp channel, String path) {
        try {
            return channel.stat(path).isDir();
        } catch (SftpException e) {
            log.debug("SFTP 目录探测视为不存在: {}", path);
            return false;
        }
    }

    /** 对象全路径:baseDir + "/" + key;baseDir 已带斜杠时不重复 */
    private String remotePath(String key) {
        String base = config.getBaseDir() == null ? "" : config.getBaseDir();
        if (base.endsWith(PATH_SEPARATOR)) {
            return base + key;
        }
        return base + PATH_SEPARATOR + key;
    }

    /** 取路径的目录段;无目录段(单段相对路径)返回 null 表示无需建目录 */
    private String parentOf(String path) {
        int end = path.lastIndexOf(PATH_SEPARATOR);
        return end <= 0 ? null : path.substring(0, end);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 一次操作的会话+通道持有;disconnect 对两件套各自幂等 */
    private record SessionHolder(com.jcraft.jsch.Session session, ChannelSftp channel) {

        private void disconnect() {
            channel.disconnect();
            session.disconnect();
        }
    }

    /** 挂在本次 SFTP 会话上的流:关闭时连同通道与会话一起断开 */
    private static final class SessionBoundInputStream extends FilterInputStream {

        private final SessionHolder holder;

        private SessionBoundInputStream(SessionHolder holder, InputStream delegate) {
            super(delegate);
            this.holder = holder;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                holder.disconnect();
            }
        }
    }
}
