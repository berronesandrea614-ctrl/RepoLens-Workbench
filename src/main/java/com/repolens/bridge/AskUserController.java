package com.repolens.bridge;

import com.repolens.common.result.Result;
import com.repolens.security.AuthUserId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * askUser 反问的回复回传端点（bridge zone）——agent 挂起提问后，前端把用户的回复经此送回，
 * 唤醒挂起的 loop 线程。提问方向走 SSE 的 "ask" 事件；回复方向走这个普通 POST。
 */
@RestController
@RequestMapping("/api/repos/{repoId}/agent")
public class AskUserController {

    private final AskUserService askUserService;

    public AskUserController(AskUserService askUserService) {
        this.askUserService = askUserService;
    }

    /** 请求体：回复某个待答问题。 */
    public record AnswerRequest(String questionId, String reply) {
    }

    /** 回传用户回复；返回是否成功交接（问题已超时/不存在则 false）。 */
    @PostMapping("/answer")
    public Result<Boolean> answer(@AuthUserId Long userId,
                                  @PathVariable Long repoId,
                                  @RequestBody AnswerRequest req) {
        return Result.success(askUserService.answer(req.questionId(), req.reply()));
    }
}
