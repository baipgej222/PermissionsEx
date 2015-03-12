/**
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ninja.leaping.permissionsex.backends.file;

import com.google.common.base.Functions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.transformation.ConfigurationTransformation;
import ninja.leaping.configurate.transformation.TransformAction;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import ninja.leaping.permissionsex.PermissionsEx;
import ninja.leaping.permissionsex.backends.AbstractDataStore;
import ninja.leaping.permissionsex.backends.LegacyConversionUtils;
import ninja.leaping.permissionsex.data.ImmutableOptionSubjectData;
import ninja.leaping.permissionsex.exception.PermissionsLoadingException;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ninja.leaping.configurate.transformation.ConfigurationTransformation.WILDCARD_OBJECT;


public class FileDataStore extends AbstractDataStore {
    public static final Factory FACTORY = new Factory("file", FileDataStore.class);

    @Setting("file")
    private String file;
    private ConfigurationLoader permissionsFileLoader;
    private ConfigurationNode permissionsConfig;

    public FileDataStore() {
        super(FACTORY);
    }

    private static ConfigurationTransformation.Builder tBuilder() {
        return ConfigurationTransformation.builder();
    }
    public void initialize(final PermissionsEx permissionsEx) throws PermissionsLoadingException {
        File permissionsFile = new File(permissionsEx.getBaseDirectory(), file);
        if (file.endsWith(".yml")) {
            File legacyPermissionsFile = permissionsFile;
            ConfigurationLoader<ConfigurationNode> yamlLoader = YAMLConfigurationLoader.builder().setFile(permissionsFile).build();
            file = file.replace(".yml", ".conf");
            permissionsFile = new File(permissionsEx.getBaseDirectory(), file);
            permissionsFileLoader = HoconConfigurationLoader.builder().setFile(permissionsFile).build();
            try {
                permissionsConfig = yamlLoader.load();
                permissionsFileLoader.save(permissionsConfig);
                legacyPermissionsFile.renameTo(new File(legacyPermissionsFile.getCanonicalPath() + ".bukkit-backup"));
            } catch (IOException e) {
                throw new PermissionsLoadingException("While loading legacy YML permissions from " + permissionsFile, e);
            }
        } else {
            permissionsFileLoader = HoconConfigurationLoader.builder().setFile(permissionsFile).build();
        }

        try {
            permissionsConfig = permissionsFileLoader.load(ConfigurationOptions.defaults());//.setMapFactory(MapFactories.unordered()));
        } catch (IOException e) {
            throw new PermissionsLoadingException("While loading permissions file from " + permissionsFile, e);
        }

        final TransformAction movePrefixSuffixDefaultAction = new TransformAction() {
            @Override
            public Object[] visitPath(ConfigurationTransformation.NodePath nodePath, ConfigurationNode configurationNode) {
                final ConfigurationNode prefixNode = configurationNode.getNode("prefix");
                if (!prefixNode.isVirtual()) {
                    configurationNode.getNode("options", "prefix").setValue(prefixNode);
                    prefixNode.setValue(null);
                }

                final ConfigurationNode suffixNode = configurationNode.getNode("suffix");
                if (!suffixNode.isVirtual()) {
                    configurationNode.getNode("options", "suffix").setValue(suffixNode);
                    suffixNode.setValue(null);
                }

                final ConfigurationNode defaultNode = configurationNode.getNode("default");
                if (!defaultNode.isVirtual()) {
                    configurationNode.getNode("options", "default").setValue(defaultNode);
                    defaultNode.setValue(null);
                }
                return null;
            }
        };

        ConfigurationTransformation versionUpdater = ConfigurationTransformation.versionedBuilder()
                .setVersionKey("schema-version")
                .addVersion(2, ConfigurationTransformation.chain(tBuilder()
                                .addAction(new Object[]{WILDCARD_OBJECT, WILDCARD_OBJECT}, new TransformAction() {
                                    @Override
                                    public Object[] visitPath(ConfigurationTransformation.NodePath nodePath, ConfigurationNode configurationNode) {
                                        Object value = configurationNode.getValue();
                                        configurationNode.setValue(null);
                                        configurationNode.getAppendedNode().setValue(value);
                                        return null;
                                    }
                                })
                                .build(),
                        tBuilder()
                                .addAction(new Object[]{WILDCARD_OBJECT, WILDCARD_OBJECT, 0, "worlds"}, new TransformAction() {
                                    @Override
                                    public Object[] visitPath(ConfigurationTransformation.NodePath nodePath, ConfigurationNode configurationNode) {
                                        ConfigurationNode entityNode = configurationNode.getParent().getParent();
                                        for (Map.Entry<Object, ? extends ConfigurationNode> ent : configurationNode.getChildrenMap().entrySet()) {
                                            entityNode.getAppendedNode().setValue(ent.getValue())
                                                    .getNode("context", "world").setValue(ent.getKey());

                                        }
                                        configurationNode.setValue(null);
                                        return null;
                                    }
                                }).build(),
                        tBuilder()
                                .addAction(new Object[]{WILDCARD_OBJECT, WILDCARD_OBJECT, WILDCARD_OBJECT, "permissions"}, new TransformAction() {
                                    @Override
                                    public Object[] visitPath(ConfigurationTransformation.NodePath nodePath, ConfigurationNode configurationNode) {
                                        List<String> existing = configurationNode.getList(Functions.toStringFunction());
                                        for (String permission : existing) {
                                            int value = permission.startsWith("-") ? -1 : 1;
                                            if (value < 0) {
                                                permission = permission.substring(1);
                                            }
                                            if (permission.equals("*")) {
                                                configurationNode.getParent().getNode("permissions-default").setValue(value);
                                                continue;
                                            }
                                            permission = LegacyConversionUtils.convertPermission(permission);
                                            if (permission.contains("*")) {
                                                permissionsEx.getLogger().warn("The permission at " + Arrays.toString(configurationNode.getPath()) + " contains a now-illegal character '*'");
                                            }
                                            configurationNode.getNode(permission).setValue(value);
                                        }
                                        return null;
                                    }
                                })
                                .addAction(new Object[]{"users", WILDCARD_OBJECT, WILDCARD_OBJECT, "group"}, new TransformAction() {
                                    @Override
                                    public Object[] visitPath(ConfigurationTransformation.NodePath nodePath, ConfigurationNode configurationNode) {
                                        Object[] retPath = nodePath.getArray();
                                        retPath[retPath.length - 1] = "parents";
                                        for (ConfigurationNode child : configurationNode.getChildrenList()) {
                                            child.setValue("group:" + child.getValue());
                                        }
                                        return retPath;
                                    }
                                })
                                .addAction(new Object[]{"groups", WILDCARD_OBJECT, WILDCARD_OBJECT, "inheritance"}, new TransformAction() {
                                    @Override
                                    public Object[] visitPath(ConfigurationTransformation.NodePath nodePath, ConfigurationNode configurationNode) {
                                        Object[] retPath = nodePath.getArray();
                                        retPath[retPath.length - 1] = "parents";
                                        for (ConfigurationNode child : configurationNode.getChildrenList()) {
                                            child.setValue("group:" + child.getValue());
                                        }
                                        return retPath;
                                    }
                                })
                                .build()))
                .addVersion(1, ConfigurationTransformation.builder()
                        .addAction(new Object[]{WILDCARD_OBJECT, WILDCARD_OBJECT}, movePrefixSuffixDefaultAction)
                        .addAction(new Object[]{WILDCARD_OBJECT, WILDCARD_OBJECT, "worlds", WILDCARD_OBJECT}, movePrefixSuffixDefaultAction)
                        .build())
                .build();
        int startVersion = permissionsConfig.getNode("schema-version").getInt(-1);
        versionUpdater.apply(permissionsConfig);
        int endVersion = permissionsConfig.getNode("schema-version").getInt();
        if (endVersion > startVersion) {
            permissionsEx.getLogger().info(permissionsFile + " schema version updated from " + startVersion + " to " + endVersion);
            save();
        }
    }

    public void close() {

    }

    private void save() throws PermissionsLoadingException {
        try {
            permissionsFileLoader.save(permissionsConfig);
        } catch (IOException e) {
            throw new PermissionsLoadingException("While saving permissions file to " + file, e);
        }
    }

    private String typeToSection(String type) {
        return type + "s";
    }

    @Override
    public ImmutableOptionSubjectData getDataInternal(String type, String identifier) throws PermissionsLoadingException {
        try {
            return FileOptionSubjectData.fromNode(permissionsConfig.getNode(typeToSection(type), identifier));
        } catch (ObjectMappingException e) {
            throw new PermissionsLoadingException("While deserializing subject data for " + type + ":" + identifier, e);
        }
    }

    @Override
    protected ListenableFuture<ImmutableOptionSubjectData> setDataInternal(String type, String identifier, ImmutableOptionSubjectData data) {
        try {
            if (data == null) {
                permissionsConfig.getNode(typeToSection(type), identifier).setValue(null);
                save();
                return null;
            }

            if (data instanceof FileOptionSubjectData) {
                ((FileOptionSubjectData) data).serialize(permissionsConfig.getNode(typeToSection(type), identifier));
            } else {
                throw new IllegalArgumentException("Data passed was not a FileOptionSubjectData");
            }
            save();
            return Futures.immediateFuture(data);
        } catch (PermissionsLoadingException | ObjectMappingException e) {
            return Futures.immediateFailedFuture(e);
        }
    }

    @Override
    public boolean isRegistered(String type, String identifier) {
        return !permissionsConfig.getNode(typeToSection(type), identifier).isVirtual();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterable<String> getAllIdentifiers(String type) {
        return (Set) this.permissionsConfig.getNode(typeToSection(type)).getChildrenMap().keySet();
    }
}