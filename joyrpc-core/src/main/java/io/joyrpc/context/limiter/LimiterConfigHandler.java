package io.joyrpc.context.limiter;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import io.joyrpc.cluster.distribution.RateLimiter;
import io.joyrpc.cluster.distribution.limiter.RateLimiterConfig;
import io.joyrpc.codec.serialization.TypeReference;
import io.joyrpc.context.ConfigEventHandler;
import io.joyrpc.extension.Extension;
import io.joyrpc.extension.MapParametric;
import io.joyrpc.extension.Parametric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.joyrpc.Plugin.JSON;
import static io.joyrpc.Plugin.LIMITER;
import static io.joyrpc.cluster.distribution.RateLimiter.DELIMITER;
import static io.joyrpc.constants.Constants.GLOBAL_SETTING;
import static io.joyrpc.constants.Constants.SETTING_INVOKE_PROVIDER_LIMIT;
import static io.joyrpc.context.limiter.LimiterConfiguration.LIMITERS;


/**
 * 限流配置信息
 */
@Extension(value = "limiter", order = ConfigEventHandler.BIZ_ORDER)
public class LimiterConfigHandler implements ConfigEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(LimiterConfigHandler.class);

    public final static String DEFAULT_LIMITER_TYPE = "leakyBucket";

    @Override
    public void handle(final String className, final Map<String, String> oldAttrs, final Map<String, String> newAttrs) {
        if (!GLOBAL_SETTING.equals(className)) {
            String oldAttr = oldAttrs.get(SETTING_INVOKE_PROVIDER_LIMIT);
            String newAttr = newAttrs.get(SETTING_INVOKE_PROVIDER_LIMIT);
            if (!Objects.equals(oldAttr, newAttr)) {
                try {
                    Map<String, RateLimiterConfig> configs = parse(newAttr);
                    Map<String, RateLimiter> limiters = load(configs, LIMITERS.get(className));
                    //全量更新
                    LIMITERS.update(className, limiters);
                } catch (Exception e) {
                    logger.error("Error occurs while parsing limiter config. caused by " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public String[] getKeys() {
        return new String[]{SETTING_INVOKE_PROVIDER_LIMIT};
    }

    /**
     * 加载配置
     *
     * @param configs
     * @param limiters
     * @return
     */
    protected Map<String, RateLimiter> load(final Map<String, RateLimiterConfig> configs, final Map<String, RateLimiter> limiters) {
        if (configs == null) {
            return null;
        }
        Map<String, RateLimiter> result = new HashMap<>(configs.size());
        configs.forEach((name, config) -> {
            RateLimiter limiter = limiters == null ? null : limiters.get(name);
            if (limiter == null || !limiter.type().equals(config.getType())) {
                limiter = load(config);
                if (limiter != null) {
                    result.put(name, limiter);
                }
            } else {
                //重新加载配置
                limiter.reload(config);
                result.put(name, limiter);
            }
        });
        return result;
    }

    /**
     * 加载配置
     *
     * @param config
     * @return
     */
    protected RateLimiter load(final RateLimiterConfig config) {
        RateLimiter rateLimiter = LIMITER.get(config.getType());
        if (rateLimiter == null) {
            //插件没有
            logger.error("rate limiter is not found. " + config.getType());
        } else {
            //重新加载配置
            rateLimiter.reload(config);
        }
        return rateLimiter;
    }

    /**
     * 解析配置
     *
     * @param limitStr
     * @return
     */
    protected Map<String, RateLimiterConfig> parse(String limitStr) {
        if (limitStr == null || limitStr.isEmpty()) {
            return null;
        }
        List<Map> results = JSON.get().parseObject(limitStr, new TypeReference<List<Map>>() {
        });
        Map<String, RateLimiterConfig> configs = new HashMap<>(results.size());
        Parametric parametric;
        //遍历限流配置
        for (Map result : results) {
            parametric = new MapParametric(result);
            String alias = parametric.getString("alias", "");
            String methodName = parametric.getString("method", "");
            String appId = parametric.getString("appId", "");
            String type = parametric.getString("type", DEFAULT_LIMITER_TYPE);
            boolean open = parametric.getBoolean("enabled", "open", Boolean.TRUE);
            int limit = parametric.getInteger("limit", 0);
            long periodNanos = TimeUnit.MILLISECONDS.toNanos(parametric.getLong("period", 1000L));
            if (type != null && open && limit > 0 && periodNanos >= TimeUnit.MILLISECONDS.toNanos(1)) {
                //限流开关没有关闭，限流数大于0，限流周期大于等于1ms
                String limitKey = String.join(DELIMITER, methodName, alias, appId);
                configs.put(limitKey, RateLimiterConfig.builder().type(type).limitCount(limit).limitPeriodNanos(periodNanos).build());
            }
        }
        return configs;
    }
}
