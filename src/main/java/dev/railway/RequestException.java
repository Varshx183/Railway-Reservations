package dev.railway;

final class RequestException extends RuntimeException {
    final String code;
    RequestException(String code, String message) { super(message); this.code = code; }
}
