package com.hamtech.bookstoreapigateway.dto;

public class IntrospectResponse {
    private Result result;

    public IntrospectResponse() {}

    public Result getResult() { return result; }
    public void setResult(Result result) { this.result = result; }

    public static class Result {
        private boolean valid;
        
        public Result() {}
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
    }
}
