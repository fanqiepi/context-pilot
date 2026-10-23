package io.github.fanqiepi.contextpilot.book;

import java.util.LinkedHashMap;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.core.MethodParameter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={ReadingController.class,io.github.fanqiepi.contextpilot.readingtask.ReadingTaskController.class,
        io.github.fanqiepi.contextpilot.readingnote.ReadingNoteProposalController.class})
public class ReadingHttpAdvice implements ResponseBodyAdvice<Object> {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReadingHttpAdvice.class);
    @ExceptionHandler(ReadingException.class)
    public ResponseEntity<?> reading(ReadingException error,HttpServletRequest request,HttpServletResponse response) {
        if(cannotWrite(error,request,response))return null;
        var body=new LinkedHashMap<String,Object>();
        body.put("code",error.code());body.put("message",error.getMessage());
        body.put("requestId",request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)==null?UUID.randomUUID().toString():request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        if(error.currentRevision()!=null)body.put("currentRevision",error.currentRevision());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception ignored,HttpServletRequest request,HttpServletResponse response) { return reading(ReadingException.invalid("INVALID_REQUEST","请求字段不正确"),request,response); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(Exception ignored,HttpServletRequest request,HttpServletResponse response) { return reading(new ReadingException(413,"IMPORT_LIMIT_EXCEEDED","文件超过上传限制"),request,response); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception error,HttpServletRequest request,HttpServletResponse response) {
        if(cannotWrite(error,request,response))return null;
        // SQL/parser exceptions can contain private text. Log only the error type.
        LOG.warn("reading.request.failed type={} requestId={}",error.getClass().getSimpleName(),request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return reading(new ReadingException(503,"READING_RETRYABLE","暂时无法完成，请查询当前状态后重试"),request,response);
    }
    private boolean cannotWrite(Exception error,HttpServletRequest request,HttpServletResponse response) {
        boolean disconnected=error instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException
                || org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(error);
        if(disconnected) {
            LOG.debug("reading.connection.closed type={} requestId={}",error.getClass().getSimpleName(),request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
            return true;
        }
        if(!response.isCommitted())return false;
        // Async dispatch may arrive after SSE headers/body were flushed. Never replace that stream with JSON.
        if(error instanceof org.springframework.web.context.request.async.AsyncRequestTimeoutException)
            LOG.debug("reading.stream.timeout requestId={}",request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        else LOG.warn("reading.stream.failed type={} requestId={}",error.getClass().getSimpleName(),request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return true;
    }
    @Override public boolean supports(MethodParameter returnType,Class<? extends HttpMessageConverter<?>> converterType) { return true; }
    @Override public Object beforeBodyWrite(Object body,MethodParameter returnType,MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,ServerHttpRequest request,ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        return body;
    }
}
