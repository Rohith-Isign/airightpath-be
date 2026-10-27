package com.rightpath.service;

import com.rightpath.dto.CompileRequest;
import com.rightpath.dto.CompileResponse;

public interface CompileService {

	CompileResponse compile(CompileRequest request);

}
