package kr.co.manty.webnettyclient;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@SpringBootApplication
@RestController
public class WebNettyClientApplication {



    public static void main(String[] args) {
        SpringApplication.run(WebNettyClientApplication.class, args);
    }

    @GetMapping("/")
    public CompletableFuture<String> helloWithNetty(@RequestParam String name) {
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        completableFuture
                .thenApply(it -> name)
                .thenAccept(it -> {
                    EventLoopGroup workerGroup = new NioEventLoopGroup();
                    try {
                        Bootstrap b = new Bootstrap(); // (1)
                        b.group(workerGroup); // (2)
                        b.channel(NioSocketChannel.class); // (3)
                        b.option(ChannelOption.SO_KEEPALIVE, true); // (4)
                        b.handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel ch) throws Exception {
                                ch.pipeline().addLast(new StringDecoder());
                                ch.pipeline().addLast(new TcpClientHandler(completableFuture, it));
                                ch.pipeline().addLast(new StringEncoder());
                            }
                        });

                        // Start the client.
                        ChannelFuture f = b.connect("localhost", 8888).sync(); // (5)

                        // Wait until the connection is closed.
                        f.channel().closeFuture().sync();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        workerGroup.shutdownGracefully();
                    }
                });



        return completableFuture;
    }

    static class TcpClientHandler extends ChannelDuplexHandler {
        private final CompletableFuture<String> completableFuture;
        private final String name;
        private final StringBuilder buffer = new StringBuilder();

        public TcpClientHandler(CompletableFuture<String> completableFuture, String name) {
            this.completableFuture = completableFuture;
            this.name = name;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.channel().writeAndFlush(this.name + "\n");
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            completableFuture.complete(buffer.toString());
            ctx.close();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if ( msg instanceof String) {
                String message = (String)msg;
                buffer.append(message);

                if ( message.length() > this.name.length()) {
                    ctx.fireChannelReadComplete();
                }
            }
        }
    }


    @Bean
    public ApplicationListener<ApplicationReadyEvent> applicationEventHandler() {
        return event -> {
            EventLoopGroup bossGroup = new NioEventLoopGroup(); // (1)
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            try {
                ServerBootstrap b = new ServerBootstrap(); // (2)
                b.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class) // (3)
                        .childHandler(new ChannelInitializer<SocketChannel>() { // (4)
                            @Override
                            public void initChannel(SocketChannel ch) throws Exception {
                                ch.pipeline().addLast(new StringDecoder());
                                ch.pipeline().addLast(new TcpServerHandler());
                                ch.pipeline().addLast(new StringEncoder());
                            }
                        })
                        .option(ChannelOption.SO_BACKLOG, 128)          // (5)
                        .childOption(ChannelOption.SO_KEEPALIVE, true); // (6)

                // Bind and start to accept incoming connections.
                ChannelFuture f = b.bind(8888).sync(); // (7)

                // Wait until the server socket is closed.
                // In this example, this does not happen, but you can do that to gracefully
                // shut down your server.
                f.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
            }
        };
    }

    static class TcpServerHandler extends ChannelDuplexHandler {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            buffer.append("Hello ");
            super.channelActive(ctx);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            ctx.channel().writeAndFlush(buffer.toString())
                    .addListener((ChannelFutureListener) future -> buffer.setLength(0));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if ( msg instanceof String) {
                String message = (String)msg;
                buffer.append(message);

                if ( message.endsWith("\n")) {
                    ctx.fireChannelReadComplete();
                }
            }
        }
    }
}
