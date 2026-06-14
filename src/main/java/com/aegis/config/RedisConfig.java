package com.aegis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    /**
     * The token-bucket script is loaded once and executed by SHA via EVALSHA
     * (Spring Data Redis handles the SCRIPT LOAD + EVALSHA, falling back to EVAL
     * on NOSCRIPT). Returning a List means each element maps to a Long.
     */
    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));

        script.setResultType(List.class);

        return script;
    }
}
