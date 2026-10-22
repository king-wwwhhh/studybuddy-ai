package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private String host = "localhost";
    private Integer port = 19530;
    private String username = "";
    private String password = "";
    private String uri = "";
    private String token = "";
    private String database = "default";
    private Long timeout = 10000L;

    /** 向量索引相关配置：选型 + 各索引类型自己的参数 */
    private Index index = new Index();

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
    }

    public static class Index {

        /** auto / FLAT / HNSW / IVF_FLAT；auto 按 collection 行数自动选 */
        private String type = "auto";
        /** HNSW 每层连接数 */
        private Integer m = 16;
        /** HNSW 建图候选宽度 */
        private Integer efConstruction = 200;
        /** IVF 桶数；0 表示按行数自动计算 */
        private Integer nlist = 0;
        /** IVF 检索探测桶数（仅 IVF_FLAT 生效） */
        private Integer nprobe = 10;
        /** HNSW 检索候选宽度（仅 HNSW 生效，运行时自动抬到 ≥ topK） */
        private Integer ef = 64;
        /** 索引类型变更时是否自动 dropIndex + 重建（默认关闭：重建期间检索不可用） */
        private Boolean autoRebuildOnChange = false;

        public Boolean getAutoRebuildOnChange() {
            return autoRebuildOnChange;
        }

        public void setAutoRebuildOnChange(Boolean autoRebuildOnChange) {
            this.autoRebuildOnChange = autoRebuildOnChange;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Integer getM() {
            return m;
        }

        public void setM(Integer m) {
            this.m = m;
        }

        public Integer getEfConstruction() {
            return efConstruction;
        }

        public void setEfConstruction(Integer efConstruction) {
            this.efConstruction = efConstruction;
        }

        public Integer getNlist() {
            return nlist;
        }

        public void setNlist(Integer nlist) {
            this.nlist = nlist;
        }

        public Integer getNprobe() {
            return nprobe;
        }

        public void setNprobe(Integer nprobe) {
            this.nprobe = nprobe;
        }

        public Integer getEf() {
            return ef;
        }

        public void setEf(Integer ef) {
            this.ef = ef;
        }
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public Long getTimeout() {
        return timeout;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public String getAddress() {
        return host + ":" + port;
    }
}
