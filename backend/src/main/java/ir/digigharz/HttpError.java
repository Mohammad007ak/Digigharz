package ir.digigharz;

import java.util.Map;

/** An error with the HTTP status and Persian message the client should see. */
public class HttpError extends RuntimeException {
  private final int status;
  private final Map<String, Object> extra;

  public HttpError(int status, String message) {
    this(status, message, Map.of());
  }

  public HttpError(int status, String message, Map<String, Object> extra) {
    super(message, null, false, false);
    this.status = status;
    this.extra = extra;
  }

  public int status() {
    return status;
  }

  public Map<String, Object> extra() {
    return extra;
  }
}
