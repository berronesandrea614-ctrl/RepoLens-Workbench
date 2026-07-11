package com.repolens.service;

import com.repolens.domain.vo.ParseRepoResultVO;

public interface JavaCodeParseService {

    ParseRepoResultVO parseRepository(Long repoId, Long userId);
}
