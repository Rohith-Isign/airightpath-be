package com.rightpath.controller;

import com.rightpath.dto.CompileRequest;
import com.rightpath.dto.CompileResponse;
import com.rightpath.service.CompileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/compile")
public class CompileController {

    @Autowired
    private CompileService compileService;

    @PostMapping
    public ResponseEntity<CompileResponse> compile(@RequestBody CompileRequest request) {
        CompileResponse response = compileService.compile(request);
        return ResponseEntity.ok(response);
    }
}