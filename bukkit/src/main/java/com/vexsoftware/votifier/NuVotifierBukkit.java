/*
 * Copyright (C) 2012 Vex Software LLC
 * This file is part of Votifier.
 *
 * Votifier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Votifier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Votifier.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vexsoftware.votifier;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.vexsoftware.votifier.forwarding.BukkitPluginMessagingForwardingSink;
import com.vexsoftware.votifier.forwarding.ForwardedVoteListener;
import com.vexsoftware.votifier.forwarding.ForwardingVoteSink;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.net.protocol.VoteInboundHandler;
import com.vexsoftware.votifier.net.protocol.VotifierGreetingHandler;
import com.vexsoftware.votifier.net.protocol.VotifierProtocolDifferentiator;
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAIO;
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAKeygen;
import com.vexsoftware.votifier.util.KeyCreator;
import com.vexsoftware.votifier.util.TokenUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * The main Votifier plugin class.
 *
 * @author Blake Beaupain
 * @author Kramer Campbell
 */
public class NuVotifierBukkit extends JavaPlugin implements VoteHandler, VotifierPlugin, ForwardedVoteListener {

    /**
     * The Votifier instance.
     */
    private static NuVotifierBukkit instance;

    /**
     * The current Votifier version.
     */
    private String version;

    /**
     * The server channel.
     */
    private Channel serverChannel;

    /**
     * The event group handling the channel.
     */
    private NioEventLoopGroup serverGroup;

    /**
     * The RSA key pair.
     */
    private KeyPair keyPair;

    /**
     * Debug mode flag
     */
    private boolean debug;

    /**
     * Keys used for websites.
     */
    private Map<String, Key> tokens = new HashMap<>();

    private ForwardingVoteSink forwardingMethod;

    @Override
    public void onEnable() {
        NuVotifierBukkit.instance = this;

        // Set the plugin version.
        version = getDescription().getVersion();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        // Handle configuration.
        File config = new File(getDataFolder() + "/config.yml");
        YamlConfiguration cfg;
        File rsaDirectory = new File(getDataFolder() + "/rsa");

        /*
         * Use IP address from server.properties as a default for
         * configurations. Do not use InetAddress.getLocalHost() as it most
         * likely will return the main server address instead of the address
         * assigned to the server.
         */
        String hostAddr = Bukkit.getServer().getIp();
        if (hostAddr == null || hostAddr.length() == 0)
            hostAddr = "0.0.0.0";

        /*
         * Create configuration file if it does not exists; otherwise, load it
         */
        if (!config.exists()) {
            try {
                // First time run - do some initialization.
                getLogger().info("Configuring Votifier for the first time...");

                // Initialize the configuration file.
                config.createNewFile();

                // Load and manually replace variables in the configuration.
                String cfgStr = new String(ByteStreams.toByteArray(getResource("bukkitConfig.yml")), StandardCharsets.UTF_8);
                String token = TokenUtil.newToken();
                cfgStr = cfgStr.replace("%default_token%", token).replace("%ip%", hostAddr);
                Files.write(cfgStr, config, StandardCharsets.UTF_8);

                /*
                 * Remind hosted server admins to be sure they have the right
                 * port number.
                 */
                getLogger().info("------------------------------------------------------------------------------");
                getLogger().info("Assigning NuVotifier to listen on port 8192. If you are hosting Craftbukkit on a");
                getLogger().info("shared server please check with your hosting provider to verify that this port");
                getLogger().info("is available for your use. Chances are that your hosting provider will assign");
                getLogger().info("a different port, which you need to specify in config.yml");
                getLogger().info("------------------------------------------------------------------------------");
                getLogger().info("Your default NuVotifier token is " + token + ".");
                getLogger().info("You will need to provide this token when you submit your server to a voting");
                getLogger().info("list.");
                getLogger().info("------------------------------------------------------------------------------");
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Error creating configuration file", ex);
                gracefulExit();
                return;
            }
        }

        // Load configuration.
        cfg = YamlConfiguration.loadConfiguration(config);

        /*
         * Create RSA directory and keys if it does not exist; otherwise, read
         * keys.
         */
        try {
            if (!rsaDirectory.exists()) {
                rsaDirectory.mkdir();
                keyPair = RSAKeygen.generate(2048);
                RSAIO.save(rsaDirectory, keyPair);
            } else {
                keyPair = RSAIO.load(rsaDirectory);
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE,
                    "Error reading configuration file or RSA tokens", ex);
            gracefulExit();
            return;
        }

        debug = cfg.getBoolean("debug", false);

        boolean setUpPort = cfg.getBoolean("enableExternal", true); //Always default to running the external port

        if (setUpPort) {
            // Load Votifier tokens.
            ConfigurationSection tokenSection = cfg.getConfigurationSection("tokens");

            if (tokenSection != null) {
                Map<String, Object> websites = tokenSection.getValues(false);
                for (Map.Entry<String, Object> website : websites.entrySet()) {
                    tokens.put(website.getKey(), KeyCreator.createKeyFrom(website.getValue().toString()));
                    getLogger().info("Loaded token for website: " + website.getKey());
                }
            } else {
                String token = TokenUtil.newToken();
                tokenSection = cfg.createSection("tokens");
                tokenSection.set("default", token);
                tokens.put("default", KeyCreator.createKeyFrom(token));
                try {
                    cfg.save(config);
                } catch (IOException e) {
                    getLogger().log(Level.SEVERE,
                            "Error generating Votifier token", e);
                    gracefulExit();
                    return;
                }
                getLogger().info("------------------------------------------------------------------------------");
                getLogger().info("No tokens were found in your configuration, so we've generated one for you.");
                getLogger().info("Your default Votifier token is " + token + ".");
                getLogger().info("You will need to provide this token when you submit your server to a voting");
                getLogger().info("list.");
                getLogger().info("------------------------------------------------------------------------------");
            }

            // Initialize the receiver.
            final String host = cfg.getString("host", hostAddr);
            final int port = cfg.getInt("port", 8192);
            if (debug)
                getLogger().info("DEBUG mode enabled!");

            final boolean disablev1 = cfg.getBoolean("disable-v1-protocol");
            if (disablev1) {
                getLogger().info("------------------------------------------------------------------------------");
                getLogger().info("Votifier protocol v1 parsing has been disabled. Most voting websites do not");
                getLogger().info("currently support the modern Votifier protocol in NuVotifier.");
                getLogger().info("------------------------------------------------------------------------------");
            }

            serverGroup = new NioEventLoopGroup(1);

            new ServerBootstrap()
                    .channel(NioServerSocketChannel.class)
                    .group(serverGroup)
                    .childHandler(new ChannelInitializer<NioSocketChannel>() {
                        @Override
                        protected void initChannel(NioSocketChannel channel) throws Exception {
                            channel.attr(VotifierSession.KEY).set(new VotifierSession());
                            channel.attr(VotifierPlugin.KEY).set(NuVotifierBukkit.this);
                            channel.pipeline().addLast("greetingHandler", new VotifierGreetingHandler());
                            channel.pipeline().addLast("protocolDifferentiator", new VotifierProtocolDifferentiator(false, !disablev1));
                            channel.pipeline().addLast("voteHandler", new VoteInboundHandler(NuVotifierBukkit.this));
                        }
                    })
                    .bind(host, port)
                    .addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                serverChannel = future.channel();
                                getLogger().info("Votifier enabled on socket "+serverChannel.localAddress()+".");
                            } else {
                                SocketAddress socketAddress = future.channel().localAddress();
                                if (socketAddress == null) {
                                    socketAddress = new InetSocketAddress(host, port);
                                }
                                getLogger().log(Level.SEVERE, "Votifier was not able to bind to " + socketAddress, future.cause());
                            }
                        }
                    });
        } else {
            getLogger().info("You have enableExternal set to false in your config.yml. NuVotifier will NOT listen to votes coming in from an external voting list.");
        }

        ConfigurationSection forwardingConfig = cfg.getConfigurationSection("forwarding");
        if (forwardingConfig != null) {
            String method = forwardingConfig.getString("method", "none").toLowerCase(); //Default to lower case for case-insensitive searches
            if ("none".equals(method)) {
                getLogger().info("Method none selected for vote forwarding: Votes will not be received from a forwarder.");
            } else if ("pluginmessaging".equals(method)) {
                String channel = forwardingConfig.getString("pluginMessaging.channel", "NuVotifier");
                try {
                    forwardingMethod = new BukkitPluginMessagingForwardingSink(this, channel, this);
                    getLogger().info("Receiving votes over PluginMessaging channel '" + channel + "'.");
                } catch (RuntimeException e) {
                    getLogger().log(Level.SEVERE, "NuVotifier could not set up PluginMessaging for vote forwarding!", e);
                }
            } else {
                getLogger().severe("No vote forwarding method '" + method + "' known. Defaulting to noop implementation.");
            }
        }
    }

    @Override
    public void onDisable() {
        // Shut down the network handlers.
        if (serverGroup != null) {
            if (serverChannel != null)
                serverChannel.close();
            serverGroup.shutdownGracefully();
        }
        if (forwardingMethod != null) {
            forwardingMethod.halt();
        }
        getLogger().info("Votifier disabled.");
    }

    private void gracefulExit() {
        getLogger().log(Level.SEVERE, "Votifier did not initialize properly!");
    }

    /**
     * Gets the instance.
     *
     * @return The instance
     */
    public static NuVotifierBukkit getInstance() {
        return instance;
    }

    /**
     * Gets the version.
     *
     * @return The version
     */
    public String getVersion() {
        return version;
    }

    public boolean isDebug() {
        return debug;
    }

    @Override
    public Map<String, Key> getTokens() {
        return tokens;
    }

    @Override
    public KeyPair getProtocolV1Key() {
        return keyPair;
    }

    @Override
    public void onVoteReceived(final Vote vote, VotifierSession.ProtocolVersion protocolVersion) throws Exception {
        if (debug) {
            if (protocolVersion == VotifierSession.ProtocolVersion.ONE) {
                getLogger().info("Got a protocol v1 vote record -> " + vote);
            } else {
                getLogger().info("Got a protocol v2 vote record -> " + vote);
            }
        }
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                Bukkit.getPluginManager().callEvent(new VotifierEvent(vote));
            }
        });
    }

    @Override
    public void onError(Channel channel, Throwable throwable) {
        if (debug) {
            getLogger().log(Level.SEVERE, "Unable to process vote from " + channel.remoteAddress(), throwable);
        } else {
            getLogger().log(Level.SEVERE, "Unable to process vote from " + channel.remoteAddress());
        }
    }

    @Override
    public void onForward(final Vote v) {
        if (debug) {
            getLogger().info("Got a forwarded vote -> " + v);
        }
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                Bukkit.getPluginManager().callEvent(new VotifierEvent(v));
            }
        });
    }
}
