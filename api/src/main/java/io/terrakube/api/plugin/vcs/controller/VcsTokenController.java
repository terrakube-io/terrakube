package io.terrakube.api.plugin.vcs.controller;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/access-token/v1/vcs")
public class VcsTokenController {
    @Autowired
    TokenService tokenService;
    @Autowired
    VcsRepository vcsRepository;

    @GetMapping("/{vcsId}")
    public ResponseEntity<String> connected(@PathVariable("vcsId") String vcsId, @RequestParam String source) {
        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).get();
        try {
            return ResponseEntity.ok(tokenService.getAccessToken(source, vcs));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
