package com.flatmaite.agreement;

import com.flatmaite.agreement.AgreementDtos.AgreementResponse;
import com.flatmaite.agreement.AgreementDtos.Signature;
import com.flatmaite.common.config.FlatmaiteProperties;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

/** Thymeleaf → XHTML → flying-saucer/OpenPDF. Pure Java, no native dependencies. */
@Service
@RequiredArgsConstructor
public class AgreementPdfService {

  private final TemplateEngine templateEngine;
  private final FlatmaiteProperties props;

  public byte[] render(AgreementResponse agreement, String appName) {
    NumberFormat inr = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    List<Signature> tenants =
        agreement.signatures().stream().filter(s -> "tenant".equals(s.role())).toList();

    Context ctx = new Context();
    ctx.setVariable("a", agreement);
    ctx.setVariable("tenants", tenants);
    ctx.setVariable("appName", appName);
    ctx.setVariable("rentFormatted", "Rs. " + inr.format(agreement.rentMonthly()) + " per month");
    ctx.setVariable("depositFormatted", "Rs. " + inr.format(agreement.deposit()));
    ctx.setVariable("generatedAt", Instant.now().toString());

    String html = templateEngine.process("agreement-pdf", ctx);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ITextRenderer renderer = new ITextRenderer();
      renderer.setDocumentFromString(html);
      renderer.layout();
      renderer.createPDF(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("PDF generation failed", e);
    }
  }

  /** Persists a copy under uploads/ and returns its public path (stored on the version row). */
  public String store(UUID agreementId, int version, byte[] pdf) {
    try {
      String key = "agreements/%s-v%d.pdf".formatted(agreementId, version);
      Path target = Paths.get(props.getStorage().getUploadDir()).resolve(key).normalize();
      Files.createDirectories(target.getParent());
      Files.write(target, pdf);
      return "/uploads/" + key;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to store agreement PDF", e);
    }
  }
}
