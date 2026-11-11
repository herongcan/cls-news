package com.herc.clsnews.source;

import com.herc.clsnews.model.RawItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假数据源：把 app.source 改成 mock 就能在完全断网的情况下验证界面和接口。
 * 每次调用都会生成新的 id，方便观察「新增高亮」是否正常。
 */
@Component
@ConditionalOnProperty(name = "app.source", havingValue = "mock")
public class MockSource implements NewsSource {

    private static final String[] SAMPLES = {
            "财联社11月11日电，新加坡海峡时报指数上涨1%至5,311.06点。",
            "财联社11月11日电，深圳：组织实施2026年第二批深圳市微短剧产业发展扶持计划申报。",
            "财联社11月11日电，北京利尔：硅碳负极材料产线将于本月底投产。",
            "财联社11月11日电，ETF两市成交额2591.86亿元，同比减少475.64亿元。",
            "财联社11月11日电，央行今日开展7天期逆回购操作，中标利率持平。"
    };

    private long seq = 0;

    @Override
    public List<RawItem> fetch() {
        long now = Instant.now().getEpochSecond();
        List<RawItem> out = new ArrayList<>();
        int n = ThreadLocalRandom.current().nextInt(1, 4);
        for (int i = 0; i < n; i++) {
            long id = 900_000_000L + (++seq);
            String text = SAMPLES[(int) (seq % SAMPLES.length)];
            String level = seq % 7 == 0 ? "A" : "C";
            out.add(new RawItem(String.valueOf(id), text, text,
                    "https://www.cls.cn/detail/" + id, now, level, "[]"));
        }
        return out;
    }

    @Override
    public String name() {
        return "mock (假数据)";
    }
}
