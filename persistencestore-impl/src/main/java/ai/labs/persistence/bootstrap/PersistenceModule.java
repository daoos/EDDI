package ai.labs.persistence.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.utilities.RuntimeUtilities;
import com.google.inject.Provides;
import com.mongodb.*;
import com.mongodb.client.MongoDatabase;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class PersistenceModule extends AbstractBaseModule {
    private final InputStream configFile;

    public PersistenceModule(InputStream configFile) {
        this.configFile = configFile;
    }

    @Override
    protected void configure() {
        registerConfigFiles(this.configFile);
    }

    @Provides
    @Singleton
    public MongoDatabase provideMongoDB(@Named("mongodb.hosts") String hosts,
                                        @Named("mongodb.port") Integer port,
                                        @Named("mongodb.database") String database,
                                        @Named("mongodb.source") String source,
                                        @Named("mongodb.username") String username,
                                        @Named("mongodb.password") String password,
                                        @Named("mongodb.connectionsPerHost") Integer connectionsPerHost,
                                        @Named("mongodb.socketKeepAlive") Boolean socketKeepAlive,
                                        @Named("mongodb.sslEnabled") Boolean sslEnabled,
                                        @Named("mongodb.requiredReplicaSetName") String requiredReplicaSetName) {
        try {

            List<ServerAddress> seeds = hostsToServerAddress(hosts, port);

            MongoClient mongoClient;
            MongoClientOptions mongoClientOptions = buildMongoClientOptions(connectionsPerHost,
                    WriteConcern.MAJORITY, ReadPreference.nearest(),
                    socketKeepAlive, sslEnabled, requiredReplicaSetName);
            if ("".equals(username) || "".equals(password)) {
                mongoClient = new MongoClient(seeds, mongoClientOptions);
            } else {
                MongoCredential credential = MongoCredential.createCredential(username, source, password.toCharArray());
                mongoClient = new MongoClient(seeds, Collections.singletonList(credential), mongoClientOptions);
            }

            registerMongoClientShutdownHook(mongoClient);

            return mongoClient.getDatabase(database);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    private MongoClientOptions buildMongoClientOptions(Integer connectionsPerHost,
                                                       WriteConcern writeConcern, ReadPreference readPreference,
                                                       Boolean socketKeepAlive, Boolean sslEnabled,
                                                       String requiredReplicaSetName) {
        MongoClientOptions.Builder builder = MongoClientOptions.builder();
        builder.writeConcern(writeConcern);
        builder.readPreference(readPreference);
        builder.connectionsPerHost(connectionsPerHost);
        builder.socketKeepAlive(socketKeepAlive);
        builder.sslEnabled(sslEnabled);
        if (!RuntimeUtilities.isNullOrEmpty(requiredReplicaSetName)) {
            builder.requiredReplicaSetName(requiredReplicaSetName);
        }
        return builder.build();
    }

    private static List<ServerAddress> hostsToServerAddress(String hosts, Integer port) throws UnknownHostException {
        List<ServerAddress> ret = new LinkedList<>();

        for (String host : hosts.split(",")) {
            ret.add(new ServerAddress(host.trim(), port));
        }

        return ret;
    }

    private void registerMongoClientShutdownHook(final MongoClient mongoClient) {
        Runtime.getRuntime().addShutdownHook(new Thread("ShutdownHook_MongoClient") {
            @Override
            public void run() {
                try {
                    mongoClient.close();
                } catch (Throwable e) {
                    String message = "MongoClient did not stop as expected.";
                    System.out.println(message);
                }
            }
        });
    }
}
