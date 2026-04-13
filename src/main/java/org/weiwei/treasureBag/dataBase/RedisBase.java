package org.weiwei.treasureBag.dataBase;


import org.weiwei.treasureBag.Main;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

public class RedisBase {

    private static JedisPool jedisPool;

    // **初始化 Redis 連接池**
    public static void init() {
        try {
            String path = "redis.";
            String host = DataBase.dataBaseConfig.getString(path + "host");
            int port = DataBase.dataBaseConfig.getInt(path + "port");
            String password = DataBase.dataBaseConfig.getString(path + "password");

            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(256);       // 最大連線數
            config.setMaxIdle(64);         // 最大閒置連線數
            config.setMinIdle(8);          // 最小閒置連線數
            config.setTestOnBorrow(true);  // 取得連線時測試
            config.setTestOnReturn(true);  // 回收連線時測試
            config.setTestWhileIdle(true); // 空閒時測試連線
            config.setBlockWhenExhausted(true); // 連線池滿時，等待可用連線
            config.setMaxWait(Duration.ofMillis(5000)); // 取得連線最多等待 5 秒
            config.setTimeBetweenEvictionRuns(Duration.ofMillis(30000)); // 每 30 秒檢查閒置連線

            jedisPool = new JedisPool(config, host, port, 10000, password);
            Main.getInst().getLogger().info("✅ Redis 連接池初始化成功！");
        } catch (Exception e) {
            Main.getInst().getLogger().warning("❌ Redis 連接池初始化失敗: " + e.getMessage());
        }
    }

    // 獲取 Jedis 實例
    public static Jedis getJedis() {
        if (jedisPool == null || jedisPool.isClosed()) {
            Main.getInst().getLogger().warning("❌ Redis 連接池未初始化，請先調用 RedisBase.init()");
        }
        return jedisPool.getResource();
    }

    // 設置鍵值
    public static String set(String key, String value) {
        try (Jedis jedis = getJedis()) {
            return jedis.set(key, value);
        }
    }

    // 獲取鍵值
    public static String get(String key) {
        try (Jedis jedis = getJedis()) {
            return jedis.get(key);
        }
    }

    // 刪除鍵
    public static Long del(String key) {
        try (Jedis jedis = getJedis()) {
            return jedis.del(key);
        }
    }

    // 設置鍵值
    public static long hSet(String key, String k, String value) {
        try (Jedis jedis = getJedis()) {
            return jedis.hset(key, k, value);
        }
    }

    public static long hSet(String key, String k, String value, int seconds) {
        try (Jedis jedis = getJedis()) {
            jedis.hset(key, k, value);
            return jedis.expire(key, seconds);
        }
    }

    // 獲取鍵值
    public static String hGet(String key, String k) {
        try (Jedis jedis = getJedis()) {
            return jedis.hget(key, k);
        }
    }

    // 刪除鍵
    public static Long hDel(String key, String k) {
        try (Jedis jedis = getJedis()) {
            return jedis.hdel(key, k);
        }
    }

    // 判斷鍵是否存在
    public static boolean exists(String key) {
        try (Jedis jedis = getJedis()) {
            return jedis.exists(key);
        }
    }

    // 設置帶有生存時間的鍵值
    public static String setWithExpiry(String key, String value, int seconds) {
        try (Jedis jedis = getJedis()) {
            return jedis.setex(key, seconds, value);
        }
    }

    // 獲取鍵的生存時間
    public static Long ttl(String key) {
        try (Jedis jedis = getJedis()) {
            return jedis.ttl(key);
        }
    }

    // 關閉 Redis 連接池
    public static void close() {
        if (jedisPool != null) {
            jedisPool.close();
            Main.getInst().getLogger().info("🛑 Redis 連接池已關閉");
        }
    }
}
