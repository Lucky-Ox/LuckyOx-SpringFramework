package com.itranswarp.luckyox.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classpath 资源扫描器。
 *
 * 根据传入的基础包名，扫描该包及其子包下的所有文件资源。
 *
 * 支持两种运行环境：
 * 1. 普通文件目录，例如 IDE 中的 target/classes；
 * 2. JAR 文件内部的资源。
 *
 * 扫描到每个资源后，会将其封装为 Resource 对象，
 * 再交给调用者传入的 mapper 函数进行过滤和转换。
 */
public class ResourceResolver {

    private static final Logger logger = LoggerFactory.getLogger(ResourceResolver.class);

    private final String basePackage;

    public ResourceResolver(String basePackage) {
        this.basePackage = basePackage;
    }

    /**
     * 扫描包下的所有资源

     * mapper 的作用：
     * 1. 接收扫描到的 Resource；
     * 2. 对 Resource 进行过滤或转换；
     * 3. 返回 null 表示忽略当前资源；
     * 4. 返回非 null 值表示加入最终结果。
     *
     * 例如：
     *
     * List<String> classNames = resolver.scan(resource -> {
     *     if (!resource.name().endsWith(".class")) {
     *         return null;
     *     }
     *     return resource.name();
     * });
     *
     * @param mapper 资源转换函数
     * @param <R>    最终结果的类型
     * @return 转换后的资源结果列表
     */
    public <R> List<R> scan(Function<Resource, R> mapper) {

        String basePackagePath = this.basePackage.replace(".", "/");

        //当前需要扫描的路径
        String path = basePackagePath;
        try {
            // 保存 mapper 转换后的最终结果
            List<R> collector = new ArrayList<>();

            // 执行真正的资源查找和扫描
            scan0(basePackagePath, path, collector, mapper);

            return collector;
        } catch (IOException e) {
            /*
             * IOException 是受检异常。
             *
             * 包装为 UncheckedIOException 后，
             * scan() 的调用者不需要显式捕获 IOException。
             */
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            /*
             * URL 转 URI 时可能抛出 URISyntaxException。
             *
             * 这里将其转换为运行时异常。
             */
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据 ClassLoader 查找指定包对应的所有 URL，
     * 并判断资源位于普通目录还是 JAR 文件中。
     *
     * 同一个包可能同时存在于多个 classpath 位置，
     * 所以 getResources() 返回的是 Enumeration<URL>。
     *
     * @param basePackagePath 基础包对应的路径，例如 com/example
     * @param path            当前需要扫描的路径
     * @param collector       保存扫描结果的集合
     * @param mapper          资源转换函数
     * @param <R>             最终结果类型
     */
    <R> void scan0(
            String basePackagePath,
            String path,
            List<R> collector,
            Function<Resource, R> mapper
    ) throws IOException, URISyntaxException {

        // 输出当前正在扫描的 classpath 路径
        logger.atDebug().log("scan path: {}", path);

        /*
         * 通过 ClassLoader 查找 path 对应的所有资源位置。
         *
         * 普通目录可能返回：
         * file:/project/target/classes/com/example
         *
         * JAR 内资源可能返回：
         * jar:file:/project/app.jar!/com/example
         */
        Enumeration<URL> en =
                getContextClassLoader().getResources(path);

        // 遍历所有包含该包的 classpath 位置
        while (en.hasMoreElements()) {

            // 获取当前资源对应的 URL
            URL url = en.nextElement();

            // 转换为 URI，便于后续创建 Path 或文件系统
            URI uri = url.toURI();

            /*
             * URI 转字符串，并删除末尾的 / 或 \。
             *
             * 例如：
             * file:/project/classes/com/example/
             *
             * 处理后：
             * file:/project/classes/com/example
             */
            String uriStr =
                    removeTrailingSlash(uriToString(uri));

            /*
             * 删除 URI 末尾的基础包路径，
             * 得到 classpath 根目录或 JAR 根位置。
             *
             * 例如：
             *
             * uriStr：
             * file:/project/classes/com/example
             *
             * basePackagePath：
             * com/example
             *
             * uriBaseStr：
             * file:/project/classes/
             */
            String uriBaseStr =
                    uriStr.substring(
                            0,
                            uriStr.length() - basePackagePath.length()
                    );

            /*
             * 普通文件 URI 以 file: 开头。
             *
             * 这里去掉 file:，
             * 便于后面通过字符串截取计算资源相对路径。
             */
            if (uriBaseStr.startsWith("file:")) {
                uriBaseStr = uriBaseStr.substring(5);
            }

            /*
             * JAR 资源和普通文件资源使用不同方式处理。
             */
            if (uriStr.startsWith("jar:")) {

                /*
                 * JAR 文件不能直接作为普通目录遍历，
                 * 需要先创建一个 JAR 文件系统，
                 * 再获取 JAR 内部对应包的 Path。
                 */
                scanFile(
                        true,
                        uriBaseStr,
                        jarUriToPath(basePackagePath, uri),
                        collector,
                        mapper
                );
            } else {

                /*
                 * 普通文件目录可以直接将 URI 转换为 Path。
                 */
                scanFile(
                        false,
                        uriBaseStr,
                        Paths.get(uri),
                        collector,
                        mapper
                );
            }
        }
    }

    /**
     * 获取用于查找 classpath 资源的 ClassLoader。
     *
     * 优先使用当前线程的上下文 ClassLoader。
     *
     * 在 Tomcat、应用服务器、插件系统等环境中，
     * 线程上下文 ClassLoader 通常能访问到更完整的资源。
     *
     * 如果线程上下文 ClassLoader 为 null，
     * 则退回使用当前类的 ClassLoader。
     *
     * @return 用于加载资源的 ClassLoader
     */
    ClassLoader getContextClassLoader() {

        // 获取当前线程的上下文 ClassLoader
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        // 如果不存在，则使用当前类的 ClassLoader
        if (cl == null) {
            cl = getClass().getClassLoader();
        }

        return cl;
    }

    /**
     * 将 JAR URI 挂载为一个 NIO 文件系统，
     * 并取得 JAR 内部基础包对应的 Path。
     *
     * JAR 本质上是 ZIP 文件。
     * FileSystems.newFileSystem() 可以让程序像访问普通目录一样
     * 访问 JAR 内部文件。
     *
     * 例如 JAR 内部存在：
     *
     * com/itranswarp/luckyox/App.class
     *
     * basePackagePath 为：
     *
     * com/itranswarp/luckyox
     *
     * 最终返回该目录对应的 Path。
     *
     * @param basePackagePath JAR 内部的基础包路径
     * @param jarUri          JAR 资源对应的 URI
     * @return JAR 内部基础包对应的 Path
     */
    Path jarUriToPath(
            String basePackagePath,
            URI jarUri
    ) throws IOException {

        return FileSystems
                .newFileSystem(jarUri, Map.of())
                .getPath(basePackagePath);
    }

    /**
     * 递归扫描指定目录下的所有普通文件。
     *
     * 无论 root 是普通磁盘目录，
     * 还是 JAR 文件系统中的虚拟目录，
     * 都可以通过 Files.walk() 统一遍历。
     *
     * @param isJar     当前是否正在扫描 JAR 文件
     * @param base      classpath 根位置或 JAR 根位置
     * @param root      开始扫描的根目录
     * @param collector 保存转换结果的集合
     * @param mapper    对每个 Resource 进行转换的函数
     * @param <R>       最终结果类型
     */
    <R> void scanFile(
            boolean isJar,
            String base,
            Path root,
            List<R> collector,
            Function<Resource, R> mapper
    ) throws IOException {

        // 删除基础路径结尾的 / 或 \
        String baseDir = removeTrailingSlash(base);

        /*
         * Files.walk(root)：
         * 递归遍历 root 以及所有子目录。
         *
         * filter(Files::isRegularFile)：
         * 只保留普通文件，排除目录。
         */
        Files.walk(root)
                .filter(Files::isRegularFile)
                .forEach(file -> {

                    // 当前文件对应的 Resource 对象
                    Resource res;

                    if (isJar) {

                        /*
                         * 扫描 JAR 文件时：
                         *
                         * baseDir 表示 JAR 的基础地址；
                         * file.toString() 表示 JAR 内部文件路径。
                         *
                         * removeLeadingSlash() 用于删除资源路径开头的斜杠。
                         */
                        res = new Resource(
                                baseDir,
                                removeLeadingSlash(file.toString())
                        );
                    } else {

                        /*
                         * 普通磁盘文件的完整路径。
                         */
                        String path = file.toString();

                        /*
                         * 从完整路径中删除 classpath 根目录，
                         * 得到资源的相对名称。
                         *
                         * 例如：
                         *
                         * path：
                         * /project/classes/com/example/User.class
                         *
                         * baseDir：
                         * /project/classes
                         *
                         * name：
                         * com/example/User.class
                         */
                        String name =
                                removeLeadingSlash(
                                        path.substring(baseDir.length())
                                );

                        /*
                         * 第一个参数表示资源的完整文件地址；
                         * 第二个参数表示资源相对于 classpath 的名称。
                         */
                        res = new Resource(
                                "file:" + path,
                                name
                        );
                    }

                    // 输出当前找到的资源
                    logger.atDebug().log(
                            "found resource: {}",
                            res
                    );

                    /*
                     * 将 Resource 交给调用者提供的 mapper。
                     *
                     * mapper 可以完成：
                     * 1. 过滤资源；
                     * 2. 转换资源；
                     * 3. 将 .class 文件转换为类名；
                     * 4. 创建 BeanDefinition；
                     * 5. 读取配置文件等。
                     */
                    R r = mapper.apply(res);

                    /*
                     * mapper 返回 null 表示忽略当前资源。
                     *
                     * 非 null 结果才会加入最终结果集合。
                     */
                    if (r != null) {
                        collector.add(r);
                    }
                });
    }

    String uriToString(URI uri) {
        return URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 删除字符串开头的一个斜杠
     */
    String removeLeadingSlash(String s) {
        if (s.startsWith("/") || s.startsWith("\\")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * 删除字符串结尾的一个斜杠
     */
    String removeTrailingSlash(String s) {
        if (s.endsWith("/") || s.endsWith("\\")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}