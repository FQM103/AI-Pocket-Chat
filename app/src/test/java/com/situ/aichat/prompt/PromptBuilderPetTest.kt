package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定宠物状态值→自然语言档位（1:1 iOS PromptBuilder+Pet 的 hunger/cleanliness/happiness/healthDescription
 * 区间）。边界反推自 iOS：hunger 20/50/70/90；cleanliness/happiness/health 80/50/30。
 */
class PromptBuilderPetTest {

    @Test fun `hunger buckets`() {
        assertEquals("吃得饱饱的，很满足", hungerDescription(0))
        assertEquals("吃得饱饱的，很满足", hungerDescription(19))
        assertEquals("有点饿了", hungerDescription(20))
        assertEquals("有点饿了", hungerDescription(49))
        assertEquals("饿了，需要喂食", hungerDescription(50))
        assertEquals("饿了，需要喂食", hungerDescription(69))
        assertEquals("很饿了，请尽快喂食", hungerDescription(70))
        assertEquals("很饿了，请尽快喂食", hungerDescription(89))
        assertEquals("快饿坏了！急需喂食", hungerDescription(90))
        assertEquals("快饿坏了！急需喂食", hungerDescription(100))
    }

    @Test fun `cleanliness buckets`() {
        assertEquals("干净整洁", cleanlinessDescription(100))
        assertEquals("干净整洁", cleanlinessDescription(80))
        assertEquals("有点脏", cleanlinessDescription(79))
        assertEquals("有点脏", cleanlinessDescription(50))
        assertEquals("脏了，需要洗澡", cleanlinessDescription(49))
        assertEquals("脏了，需要洗澡", cleanlinessDescription(30))
        assertEquals("很脏，请尽快清洁", cleanlinessDescription(29))
        assertEquals("很脏，请尽快清洁", cleanlinessDescription(0))
    }

    @Test fun `happiness buckets`() {
        assertEquals("非常开心愉快", happinessDescription(100))
        assertEquals("非常开心愉快", happinessDescription(80))
        assertEquals("心情不错", happinessDescription(79))
        assertEquals("心情不错", happinessDescription(50))
        assertEquals("有点低落", happinessDescription(49))
        assertEquals("有点低落", happinessDescription(30))
        assertEquals("难过又孤单，需要关注", happinessDescription(29))
        assertEquals("难过又孤单，需要关注", happinessDescription(0))
    }

    @Test fun `health buckets`() {
        assertEquals("健康", healthDescription(100))
        assertEquals("健康", healthDescription(80))
        assertEquals("有点不舒服", healthDescription(79))
        assertEquals("有点不舒服", healthDescription(50))
        assertEquals("感觉不太好", healthDescription(49))
        assertEquals("感觉不太好", healthDescription(30))
        assertEquals("生病了，需要照顾", healthDescription(29))
        assertEquals("生病了，需要照顾", healthDescription(0))
    }
}
