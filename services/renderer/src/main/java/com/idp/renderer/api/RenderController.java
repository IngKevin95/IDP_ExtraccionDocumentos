package com.idp.renderer.api;

import com.idp.renderer.core.RenderService;
import com.idp.renderer.core.ResultPackage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class RenderController {

    private final RenderService service;

    public RenderController(RenderService service) {
        this.service = service;
    }

    @PostMapping(value = "/v1/render", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void render(@RequestParam("file") MultipartFile file, HttpServletResponse response)
            throws IOException {
        Path in = Files.createTempFile("render-in-", ".bin");
        try {
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, in, StandardCopyOption.REPLACE_EXISTING);
            }
            try (ResultPackage pkg = service.render(in)) {
                response.setContentType("application/zip");
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"render.zip\"");
                response.setContentLengthLong(pkg.size());
                pkg.copyTo(response.getOutputStream());
            }
        } finally {
            Files.deleteIfExists(in);
        }
    }
}
