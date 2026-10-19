package uy.edu.tic.platform.domain;

public class BusinessException extends RuntimeException {
  public final String code;

  public BusinessException(String code, String message) {
    super(message);
    this.code = code;
  }
}
