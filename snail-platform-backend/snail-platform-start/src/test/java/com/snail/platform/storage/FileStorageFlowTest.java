package com.snail.platform.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snail.platform.framework.storage.SftpTestServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 职责:文件存储端到端回归——内嵌 SFTP 起 SFTP 服务,走"上传→列表→平台检索→下载字节一致→
 * 白名单拒绝→删除→再下载按不存在拒绝",外加配额拒绝、跨租户隐身、文件名清洗三条设计口径用例;
 * 并覆盖下载权限码、文件级可见性、停用用户 401、按 storage_type 路由与并发配额。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。上传的文件与元数据行有意残留,与阶段③流程测试同一口径。
 * 配额用例改过本租户的 storage_limit_mb,收尾恢复原值;跨租户与类型路由用例直插元数据行,
 * 断言完即删——这两类都要动别人的数据,不留残留是底线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class FileStorageFlowTest {

    /** 内嵌 SFTP 夹具:端口随机、口令认证、虚拟根落在构建目录内,不污染工作区 */
    private static final SftpTestServer SFTP =
            new SftpTestServer(Paths.get("./target/sftp-home").toAbsolutePath().normalize());

    /** 下载名回显的 RFC 5987 前缀,与 FileController 的 Content-Disposition 对齐 */
    private static final String DISPOSITION_MARK = "filename*=UTF-8''";

    /** 文件所属租户(默认租户),配额与跨租户用例的数据锚点 */
    private static final long TENANT_ID = 1L;

    /** 跨租户用例的伪造租户,库内没有对应账号,只能靠直插元数据行造出来 */
    private static final long FOREIGN_TENANT_ID = 9_902_001L;

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");
    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern FILE_ID = Pattern.compile("\"fileId\":(\\d+)");
    private static final Pattern FILE_NAME = Pattern.compile("\"fileName\":\"([^\"]*)\"");

    /** 新建测试用户的统一口令 */
    private static final String PASSWORD = "User@1234";

    /** 白名单拒绝文案的判别片段 */
    private static final String NOT_IN_WHITELIST = "白名单";

    /** 删除后再下载的拒绝文案 */
    private static final String FILE_MISSING = "文件不存在";

    /** 配额不足的统一文案 */
    private static final String QUOTA_DENIED = "配额";

    /** 字节换算 MB 的基数,与 FileStorageService 同一口径 */
    private static final int MB = 1024 * 1024;

    /** 跨租户用例的假键:下载在元数据闸就被拒,永远不会走到存储端口 */
    private static final String FOREIGN_STORAGE_KEY = "9902001/197001/flow-test-foreign-file";

    @AfterAll
    static void stopEmbeddedSftpServer() {
        SFTP.close();
    }

    /** 全链 MVC:认证上下文、租户拦截器、存储端口全部真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 造数据用:配额列改写、跨租户元数据行直插与清理 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("snail.storage.type", () -> "sftp");
        registry.add("snail.storage.sftp.host", () -> "127.0.0.1");
        registry.add("snail.storage.sftp.port", () -> String.valueOf(SFTP.port()));
        registry.add("snail.storage.sftp.username", () -> SftpTestServer.USERNAME);
        registry.add("snail.storage.sftp.password", () -> SftpTestServer.PASSWORD);
        // 虚拟根即 SFTP 根:/ + 对象键,物理位置就是夹具目录下的 <键>
        registry.add("snail.storage.sftp.base-dir", () -> "/");
        // 内嵌服务端主机密钥随机自签,夹具显式关闭严格指纹;生产默认开启
        registry.add("snail.storage.sftp.strict-host-key-checking", () -> "false");
    }

    @Test
    void fileFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String fileName = "storage-flow-" + suffix + ".txt";
        byte[] content = ("snail storage flow " + suffix).getBytes(StandardCharsets.UTF_8);
        String adminToken = login("admin", "admin123");

        // 上传 → 元数据返回 fileId
        long fileId = upload(adminToken, fileName, content);
        assertTrue(fileId > 0, "上传应返回文件 ID: " + fileId);

        // 本租户列表可见
        String page = page(adminToken, "/system/file/page");
        assertTrue(page.contains("\"fileId\":" + fileId), "列表未见上传文件: " + page);
        assertTrue(page.contains(q(fileName)), "列表未见上传文件名: " + page);

        // 平台面跨租户检索可见
        String platformPage = page(adminToken, "/platform/file/page");
        assertTrue(platformPage.contains("\"fileId\":" + fileId), "平台检索未见上传文件: " + platformPage);

        // 下载字节一致,下载名走 RFC 5987 回显
        MvcResult started = mockMvc.perform(get("/system/file/" + fileId + "/raw")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatched = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String disposition = dispatched.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition != null && disposition.contains(DISPOSITION_MARK),
                "下载头缺 RFC 5987 文件名: " + disposition);
        assertArrayEquals(content, dispatched.getResponse().getContentAsByteArray(), "下载内容与上传不一致");

        // 白名单 deny-by-default:.exe 天然拒绝
        String denied = uploadResponse(adminToken, "evil-" + suffix + ".exe",
                "no".getBytes(StandardCharsets.UTF_8), "flow-test");
        assertTrue(jsonCode(denied) != 0 && denied.contains(NOT_IN_WHITELIST),
                "白名单外扩展名应被拒绝: " + denied);

        // 删除 → 再下载按"文件不存在"拒绝(存在性即租户隐私)
        String removed = mockMvc.perform(delete("/system/file/" + fileId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(removed), "删除文件失败: " + removed);

        String missing = downloadBody(adminToken, fileId);
        assertTrue(jsonCode(missing) != 0 && missing.contains(FILE_MISSING),
                "删除后再下载应按文件不存在拒绝: " + missing);
    }

    /**
     * 配额口径(storageLimitMb 为 NULL 不限;占用取全租户 SUM):额度放得下第一笔、放不下第二笔
     * (第二笔刻意大于剩余额度,避免 MB 取整把断言漂成偶发),两笔的成败即证明 SUM 与额度两头都在生效。
     */
    @Test
    void quotaRejectsUploadBeyondTenantLimit() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String bizType = "quota-" + suffix;
        String adminToken = login("admin", "admin123");
        Integer originalLimit = storageLimitMb();
        long firstSize = 300 * 1024L;
        // 第二笔取 2MB:额度按整数 MB 上取整,剩余空间最宽也到不了 2MB,拒绝才是确定性的
        long secondSize = 2 * MB;
        long usedBefore = usedSizeBytes();
        try {
            // 额度 = 既有占用 + 1MB:够第一笔 300KB,不够再加 900KB
            updateStorageLimit((int) ((usedBefore + MB) / MB + 1));

            upload(adminToken, "quota-first-" + suffix + ".txt",
                    randomBytes((int) firstSize, suffix), bizType);
            String denied = uploadResponse(adminToken, "quota-second-" + suffix + ".txt",
                    randomBytes((int) secondSize, suffix), bizType);
            assertTrue(denied.contains(QUOTA_DENIED), "超出租户配额应被拒绝: " + denied);
            assertEquals(400, jsonCode(denied), "配额拒绝应走 badRequest 400: " + denied);

            // 额度内的占用确实进了元数据,否则上一条拒绝只是空跑
            assertTrue(usedSizeBytes() >= usedBefore + firstSize,
                    "首笔上传应计入租户已用字节: before=" + usedBefore + ", now=" + usedSizeBytes());
        } finally {
            updateStorageLimit(originalLimit);
        }
    }

    /**
     * 跨租户即不存在(§7:存在性即租户隐私):外来租户的元数据行对本租户的下载与本租户列表都不可见,
     * 只有平台面跨租户检索能看到——三种可见面各断一次,少一条就分不清是拦截器还是权限闸生效。
     */
    @Test
    void foreignTenantFileIsInvisibleOnTenantSide() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String bizType = "foreign-" + suffix;
        String adminToken = login("admin", "admin123");
        long foreignFileId = insertForeignFile(bizType, suffix);
        try {
            String denied = downloadBody(adminToken, foreignFileId);
            assertTrue(denied.contains(FILE_MISSING), "跨租户下载应按不存在拒绝: " + denied);
            assertEquals(400, jsonCode(denied), "跨租户拒绝应走 badRequest 400: " + denied);

            String ownPage = page(adminToken, "/system/file/page", "pageSize", 200, "bizType", bizType);
            assertFalse(ownPage.contains("\"fileId\":" + foreignFileId),
                    "跨租户文件不应出现在本租户列表: " + ownPage);

            String platformPage = page(adminToken, "/platform/file/page", "pageSize", 200, "bizType", bizType);
            assertTrue(platformPage.contains("\"fileId\":" + foreignFileId),
                    "平台面跨租户检索应见该文件: " + platformPage);
            assertTrue(platformPage.contains("\"tenantId\":" + FOREIGN_TENANT_ID),
                    "平台面结果应带出真实租户: " + platformPage);
        } finally {
            jdbcTemplate.update("DELETE FROM sys_file WHERE file_id = ?", foreignFileId);
        }
    }

    /** 文件名清洗(§5 校验链第 1 步):去路径段只留尾段、非法字符替换、截断保扩展名,原始输入永不进下载路径 */
    @Test
    void uploadCleansPathSegmentsAndControlChars() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        // 路径以分隔符收尾,尾段才是控制字符+报告名;BEL 与 " <> | 各替换成一个下划线
        String hostile = "C:\\Users\\snail\\..\\..\\" + (char) 7 + "报告\"<>|.txt";
        String response = uploadRaw(adminToken, hostile,
                ("clean-" + suffix).getBytes(StandardCharsets.UTF_8), "clean-test");
        String cleaned = extract(FILE_NAME, response);
        assertEquals("_报告____.txt", cleaned, "路径段与控制字符应被清洗掉");
        assertFalse(cleaned.contains(".."), "清洗后的文件名不该再含上级路径段: " + cleaned);

        byte[] filler = randomBytes(1024, suffix);
        String longName = "a".repeat(300) + ".txt";
        String longResponse = uploadRaw(adminToken, longName, filler, "clean-test");
        String truncated = extract(FILE_NAME, longResponse);
        assertEquals(255, truncated.length(), "超长文件名应截断到列宽: " + truncated.length());
        assertTrue(truncated.endsWith(".txt"), "截断必须保住扩展名: " + truncated);
    }

    /**
     * 下载的双层闸:权限码回答"能不能下载",FileAccessPolicy 回答"这一条能不能读"。
     * 无下载码 403;有码但非上传者且非文件管理者被文件级可见性拒绝;上传者本人与租户管理员可读;
     * 停用用户旧令牌在认证闸即 401,不会走到权限码。
     */
    @Test
    void downloadNeedsPermissionAndFileAccessPolicy() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");
        byte[] adminContent = ("admin-file-" + suffix).getBytes(StandardCharsets.UTF_8);
        long adminFile = upload(adminToken, "acl-admin-" + suffix + ".txt", adminContent, "acl-" + suffix);

        long downloadRole = createRole("下载角色" + suffix, "file_dl_" + suffix, adminToken);
        grantMenus(downloadRole, 180L, 1802L, 1803L);
        long listRole = createRole("列表角色" + suffix, "file_ls_" + suffix, adminToken);
        grantMenus(listRole, 180L);

        String downloadUser = "aclFull" + suffix;
        String listUser = "aclList" + suffix;
        long downloadUserId = createUser(downloadUser, downloadRole, adminToken);
        long listUserId = createUser(listUser, listRole, adminToken);
        String downloadToken = login(downloadUser, PASSWORD);
        String listToken = login(listUser, PASSWORD);
        try {
            // 无 system:file:download:权限闸直接拒绝
            mockMvc.perform(get("/system/file/" + adminFile + "/raw")
                            .header("Authorization", "Bearer " + listToken))
                    .andExpect(status().isForbidden());

            // 有下载码但不是上传者、也不是文件管理者:文件级可见性拒绝
            String denied = downloadBody(downloadToken, adminFile);
            assertEquals(403, jsonCode(denied), "非上传者应被文件级可见性拒绝: " + denied);
            assertTrue(denied.contains("无权访问"), denied);

            // 上传者本人可读自己的文件
            byte[] ownContent = ("own-" + suffix).getBytes(StandardCharsets.UTF_8);
            long ownFile = upload(downloadToken, "acl-own-" + suffix + ".txt", ownContent, "acl-" + suffix);
            assertArrayEquals(ownContent, downloadResult(downloadToken, ownFile)
                    .getResponse().getContentAsByteArray(), "上传者本人应能读自己的文件");

            // 租户管理员可读他人文件
            assertArrayEquals(ownContent, downloadResult(adminToken, ownFile)
                    .getResponse().getContentAsByteArray(), "租户管理员应可读全部文件");

            // 停用用户后旧令牌下载 401:认证闸先于权限码与文件策略
            changeUserStatus(downloadUserId, 1, adminToken);
            mockMvc.perform(get("/system/file/" + ownFile + "/raw")
                            .header("Authorization", "Bearer " + downloadToken))
                    .andExpect(status().isUnauthorized());
        } finally {
            deleteUser(downloadUserId, adminToken);
            deleteUser(listUserId, adminToken);
            deleteRole(downloadRole, adminToken);
            deleteRole(listRole, adminToken);
        }
    }

    /** 元数据里的 storage_type 没配后端时明确拒绝,不静默回退当前 sftp 去读错对象 */
    @Test
    void downloadRejectsUnconfiguredStoredBackend() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");
        String storageKey = TENANT_ID + "/197001/legacy-" + suffix + ".txt";
        long fileId = insertFileWithStorageType("route-" + suffix, "minio-legacy", storageKey);
        try {
            String denied = downloadBody(adminToken, fileId);
            assertEquals(400, jsonCode(denied), "未配置存储后端应走业务拒绝: " + denied);
            assertTrue(denied.contains("minio-legacy"), "报错应点名缺失的后端类型: " + denied);
        } finally {
            jdbcTemplate.update("DELETE FROM sys_file WHERE file_id = ?", fileId);
        }
    }

    /**
     * 并发配额:额度只够一笔时,两笔同时上传必须一成一败。
     * 两笔都能通过无锁预检,胜负由锁租户行后的重算决定;失败笔的对象应被补偿清理。
     */
    @Test
    void concurrentUploadsCannotExceedQuota() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");
        Integer originalLimit = storageLimitMb();
        long usedBefore = usedSizeBytes();
        long size = MB;
        // 额度按 MB 上取整到"恰好够一笔":剩余区间 [1MB, 2MB),两笔必然放不下
        updateStorageLimit((int) ((usedBefore + size + MB - 1) / MB));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<String> upload = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return uploadResponse(adminToken, "race-race-" + System.nanoTime() + ".txt",
                        randomBytes((int) size, suffix), "race-" + suffix);
            };
            Future<String> first = pool.submit(upload);
            Future<String> second = pool.submit(upload);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "上传线程未就绪");
            go.countDown();
            String firstResponse = first.get(60, TimeUnit.SECONDS);
            String secondResponse = second.get(60, TimeUnit.SECONDS);

            long accepted = Stream.of(firstResponse, secondResponse)
                    .filter(response -> jsonCode(response) == 0)
                    .count();
            assertEquals(1, accepted, "并发上传只能有一笔成功: " + firstResponse + " | " + secondResponse);
            String rejected = jsonCode(firstResponse) == 0 ? secondResponse : firstResponse;
            assertTrue(rejected.contains(QUOTA_DENIED), "失败笔应为配额拒绝: " + rejected);
            assertEquals(usedBefore + size, usedSizeBytes(), "成功笔应恰好计入一份配额");
        } finally {
            pool.shutdownNow();
            updateStorageLimit(originalLimit);
        }
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private long upload(String token, String fileName, byte[] content) throws Exception {
        return upload(token, fileName, content, "flow-test");
    }

    private long upload(String token, String fileName, byte[] content, String bizType) throws Exception {
        return fileIdOf(uploadRaw(token, fileName, content, bizType));
    }

    /** 上传并回传完整响应体,需要断言清洗后文件名等字段时用这条 */
    private String uploadRaw(String token, String fileName, byte[] content, String bizType) throws Exception {
        String response = uploadResponse(token, fileName, content, bizType);
        assertEquals(0, jsonCode(response), "上传失败: " + response);
        return response;
    }

    /** 上传的裸响应:白名单与配额这类"期望被拒"的断言要在非零 code 上取文案,不能被前置断言掐掉 */
    private String uploadResponse(String token, String fileName, byte[] content, String bizType) throws Exception {
        return mockMvc.perform(multipart("/system/file/upload")
                        .file(new MockMultipartFile("file", fileName,
                                MediaType.TEXT_PLAIN_VALUE, content))
                        .param("bizType", bizType)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String page(String token, String url) throws Exception {
        return page(token, url, "pageSize", 50);
    }

    /** 分页查询的裸响应;参数按 k,v 交替传入 */
    private String page(String token, String url, Object... params) throws Exception {
        var request = get(url).header("Authorization", "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(String.valueOf(params[i]), String.valueOf(params[i + 1]));
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 下载并回体:成功链走异步流,语义拒绝是同步 JSON 信封,按是否已启动异步分派取响应 */
    private String downloadBody(String token, long fileId) throws Exception {
        MvcResult result = mockMvc.perform(get("/system/file/" + fileId + "/raw")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andReturn();
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long fileIdOf(String response) {
        return Long.parseLong(extract(FILE_ID, response));
    }

    /** 指定租户的存储配额(NULL 为不限) */
    private Integer storageLimitMb() {
        return jdbcTemplate.queryForObject(
                "SELECT storage_limit_mb FROM sys_tenant WHERE tenant_id = ?", Integer.class, TENANT_ID);
    }

    /** 改写租户配额;传 null 即恢复"不限" */
    private void updateStorageLimit(Integer limitMb) {
        jdbcTemplate.update("UPDATE sys_tenant SET storage_limit_mb = ? WHERE tenant_id = ?",
                limitMb, TENANT_ID);
    }

    /** 本租户已用字节:与 FileStorageService.usedBytes 同一算法,SUM(size) 走租户拦截器圈定 */
    private long usedSizeBytes() {
        Number used = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(`size`), 0) FROM sys_file WHERE tenant_id = " + TENANT_ID
                        + " AND deleted = 0", Number.class);
        return used == null ? 0L : used.longValue();
    }

    /** 造一条外来租户的元数据行:库里没有第二个租户的账号,只能直插;键指向不存在的位置也没关系,下载在元数据闸就被拒 */
    private long insertForeignFile(String bizType, String suffix) {
        jdbcTemplate.update("INSERT INTO sys_file "
                        + "(tenant_id, file_name, ext, content_type, size, storage_key, storage_type, "
                        + "biz_type, upload_by, upload_by_name, create_time, update_time, deleted) "
                        + "VALUES (?, ?, 'txt', 'text/plain', ?, ?, 'sftp', ?, ?, '外部租户', NOW(), NOW(), 0)",
                FOREIGN_TENANT_ID, "foreign-" + suffix + ".txt", 4L, FOREIGN_STORAGE_KEY,
                bizType, FOREIGN_TENANT_ID);
        Long fileId = jdbcTemplate.queryForObject(
                "SELECT file_id FROM sys_file WHERE storage_key = ?", Long.class, FOREIGN_STORAGE_KEY);
        assertTrue(fileId != null && fileId > 0, "跨租户元数据行应插入成功");
        return fileId;
    }

    /** 建测试角色:数据权限取"仅本人",与下载权限面无关 */
    private long createRole(String name, String code, String token) throws Exception {
        String response = postJson("/system/role",
                "{\"roleName\":\"" + name + "\",\"roleCode\":\"" + code + "\",\"dataScope\":4,\"sort\":9}",
                token);
        assertEquals(0, jsonCode(response), "建角色失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    /** 角色授权直接落库:授权接口另有用例覆盖,这里只负责把权限码挂上 */
    private void grantMenus(long roleId, long... menuIds) {
        for (long menuId : menuIds) {
            jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
    }

    private long createUser(String username, long roleId, String token) throws Exception {
        String response = postJson("/system/user",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\","
                        + "\"nickname\":\"文件权限测试\",\"orgId\":1,\"roleIds\":[" + roleId + "]}", token);
        assertEquals(0, jsonCode(response), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    /** 停用/启用用户:停用会自增 token_version,旧令牌随即失效 */
    private void changeUserStatus(long userId, int status, String token) throws Exception {
        String response = mockMvc.perform(put("/system/user/" + userId + "/status")
                        .param("status", String.valueOf(status))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "改用户状态失败: " + response);
    }

    private void deleteUser(long userId, String token) throws Exception {
        String response = mockMvc.perform(delete("/system/user/" + userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "删除测试用户失败: " + response);
    }

    private void deleteRole(long roleId, String token) throws Exception {
        String response = mockMvc.perform(delete("/system/role/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "删除测试角色失败: " + response);
    }

    /** 成功下载的完整响应:异步流必须在分派后才拿到字节 */
    private MvcResult downloadResult(String token, long fileId) throws Exception {
        MvcResult started = mockMvc.perform(get("/system/file/" + fileId + "/raw")
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 直插一条指定 storage_type 的本租户元数据行:验证按行内类型路由,而不是回退当前生效后端 */
    private long insertFileWithStorageType(String bizType, String storageType, String storageKey) {
        jdbcTemplate.update("INSERT INTO sys_file "
                        + "(tenant_id, file_name, ext, content_type, size, storage_key, storage_type, "
                        + "biz_type, upload_by, upload_by_name, create_time, update_time, deleted) "
                        + "VALUES (?, ?, 'txt', 'text/plain', ?, ?, ?, ?, 1, '超级管理员', NOW(), NOW(), 0)",
                TENANT_ID, bizType + ".txt", 4L, storageKey, storageType, bizType);
        Long fileId = jdbcTemplate.queryForObject(
                "SELECT file_id FROM sys_file WHERE storage_key = ?", Long.class, storageKey);
        assertTrue(fileId != null && fileId > 0, "路由用例元数据行应插入成功");
        return fileId;
    }

    private String postJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 造定长内容:用唯一标记按自身长度重复铺满,只为凑到目标体积 */
    private static byte[] randomBytes(int size, String seed) {
        byte[] content = new byte[size];
        byte[] mark = ("snail-storage-flow-" + seed).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < size; i += mark.length) {
            System.arraycopy(mark, 0, content, i, Math.min(mark.length, size - i));
        }
        return content;
    }

    private String login(String username, String password) throws Exception {
        String captchaKey = "it-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            String response = mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                    + "\",\"captchaKey\":\"" + captchaKey + "\",\"captchaCode\":\"ABCD\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(response.contains("\"token\""), "登录失败: " + username + " -> " + response);
            return extract(TOKEN, response);
        } finally {
            redisTemplate.delete(redisKey);
        }
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "响应中未找到 " + pattern + " : " + text);
        return matcher.group(1);
    }

    private static int jsonCode(String text) {
        return Integer.parseInt(extract(CODE, text));
    }
}
