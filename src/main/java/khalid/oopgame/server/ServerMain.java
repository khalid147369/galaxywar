package khalid.oopgame.server;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AttributeKey;

public final class ServerMain {
    static final AttributeKey<String> PLAYER_ID = AttributeKey.valueOf("playerId");
    static final AttributeKey<String> ROOM_CODE = AttributeKey.valueOf("roomCode");
    private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        configureLogging(config.logLevel);
        ObjectMapper mapper = new ObjectMapper();
        JdbcRepository repository = new JdbcRepository(config, mapper);
        RoomManager rooms = new RoomManager(mapper, repository, config);
        SslContext sslContext = createSslContext(config);
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();
        java.util.concurrent.ScheduledExecutorService simulation =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "odyssey-room-simulation");
                thread.setDaemon(true);
                return thread;
            });
        long tickMillis = Math.max(16L, 1000L / config.tickRateHz);
        simulation.scheduleAtFixedRate(() -> {
            try {
                rooms.tick(tickMillis / 1000f);
            } catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "Room simulation tick failed.", exception);
            }
        }, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        simulation.scheduleAtFixedRate(() -> {
            try {
                rooms.persistAll();
            } catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "Periodic room persistence failed.", exception);
            }
        }, 10L, 10L, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            simulation.shutdownNow();
            try {
                rooms.persistAll();
            } catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "Final room persistence failed.", exception);
            }
            boss.shutdownGracefully();
            workers.shutdownGracefully();
            repository.close();
        }, "odyssey-shutdown"));

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        if (sslContext != null) {
                            channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
                        }
                        channel.pipeline()
                            .addLast(new LineBasedFrameDecoder(65536))
                            .addLast(new StringDecoder(java.nio.charset.StandardCharsets.UTF_8))
                            .addLast(new StringEncoder(java.nio.charset.StandardCharsets.UTF_8))
                            .addLast(new ClientHandler(mapper, rooms));
                    }
                });
            Channel channel = bootstrap.bind("0.0.0.0", config.port).sync().channel();
            LOGGER.info("Odyssey multiplayer server listening on 0.0.0.0:" + config.port);
            channel.closeFuture().sync();
        } finally {
            simulation.shutdownNow();
            boss.shutdownGracefully().sync();
            workers.shutdownGracefully().sync();
            repository.close();
        }
    }

    private static SslContext createSslContext(ServerConfig config) throws Exception {
        if (!config.tlsEnabled) {
            return null;
        }
        boolean base64Configured = config.tlsCertificateBase64 != null
            && config.tlsPrivateKeyBase64 != null;
        boolean pathsConfigured = config.tlsCertificatePath != null
            && config.tlsPrivateKeyPath != null;
        if (!base64Configured && !pathsConfigured) {
            throw new IllegalStateException(
                "Configure both TLS_CERTIFICATE_BASE64/TLS_PRIVATE_KEY_BASE64 or both TLS certificate paths."
            );
        }
        if (base64Configured) {
            byte[] certificate;
            byte[] privateKey;
            try {
                certificate = java.util.Base64.getDecoder().decode(config.tlsCertificateBase64);
                privateKey = java.util.Base64.getDecoder().decode(config.tlsPrivateKeyBase64);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("TLS certificate/key variables must be valid Base64.", exception);
            }
            return SslContextBuilder.forServer(
                new java.io.ByteArrayInputStream(certificate),
                new java.io.ByteArrayInputStream(privateKey),
                config.tlsPrivateKeyPassword
            ).build();
        }
        java.io.File certificate = new java.io.File(config.tlsCertificatePath);
        java.io.File privateKey = new java.io.File(config.tlsPrivateKeyPath);
        if (!certificate.isFile() || !privateKey.isFile()) {
            throw new IllegalStateException("Configured TLS certificate/key files do not exist.");
        }
        return SslContextBuilder.forServer(
            certificate,
            privateKey,
            config.tlsPrivateKeyPassword
        ).build();
    }

    private static void configureLogging(String logLevel) {
        Level level = Level.parse(logLevel);
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(level);
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(level);
        }
    }
}
