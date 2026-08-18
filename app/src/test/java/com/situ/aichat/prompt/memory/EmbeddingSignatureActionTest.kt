package com.situ.aichat.prompt.memory

import com.situ.aichat.prompt.memory.VectorMemoryService.SignatureAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [VectorMemoryService.signatureAction] 单测——断言反推 iOS `detectAndHandleModelChangeIfNeeded` 分支：
 * 签名一致不动 / 首装只记录不清 / 签名变更且可用才清空重嵌 / 签名变更但不可用则推迟（安卓独有安全位）。
 */
class EmbeddingSignatureActionTest {

    private val current = VectorMemoryService.MODEL_SIGNATURE

    @Test fun sameSignature_noChange_whenAvailable() {
        assertEquals(SignatureAction.NO_CHANGE, VectorMemoryService.signatureAction(current, current, embedderAvailable = true))
    }

    @Test fun sameSignature_noChange_evenWhenUnavailable() {
        // 一致优先于可用性：常态启动不应因嵌入器暂不可用而误判。
        assertEquals(SignatureAction.NO_CHANGE, VectorMemoryService.signatureAction(current, current, embedderAvailable = false))
    }

    @Test fun emptySaved_firstInstall_recordsOnly_noClear() {
        // 对齐 iOS：savedSig 空 = 全新安装，不迁移/不清空，仅记录当前签名。
        assertEquals(SignatureAction.RECORD_FIRST_INSTALL, VectorMemoryService.signatureAction("", current, embedderAvailable = true))
        assertEquals(SignatureAction.RECORD_FIRST_INSTALL, VectorMemoryService.signatureAction("", current, embedderAvailable = false))
    }

    @Test fun changedSignature_available_clearsAndReembeds() {
        // 模型换过（如旧 dim384 → 新 dim512）且嵌入器可用：清空全部旧向量待重嵌。
        assertEquals(
            SignatureAction.CLEAR_AND_REEMBED,
            VectorMemoryService.signatureAction("legacy-other-dim384", current, embedderAvailable = true),
        )
    }

    @Test fun changedSignature_unavailable_defers_noClear() {
        // 安卓无 fallback 嵌入器：模型加载不了就盲清会永久孤儿化向量，故推迟、连签名也不更新，下次启动重试。
        assertEquals(
            SignatureAction.DEFER_UNAVAILABLE,
            VectorMemoryService.signatureAction("legacy-other-dim384", current, embedderAvailable = false),
        )
    }

    @Test fun sameDimDifferentModel_stillTreatedAsChange() {
        // 关键缺口场景：未来换同维度(512)不同模型，每搜的 dim 守卫拦不住 → 必须靠签名变更触发重嵌。
        assertEquals(
            SignatureAction.CLEAR_AND_REEMBED,
            VectorMemoryService.signatureAction("onnx-some-other-zh-model-dim512", current, embedderAvailable = true),
        )
    }
}
