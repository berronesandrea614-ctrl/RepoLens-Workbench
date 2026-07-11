import { describe, expect, it } from "vitest";
import {
  hasLoginErrors,
  hasRegisterErrors,
  validateLoginForm,
  validateRegisterForm,
} from "./loginValidation";

describe("validateLoginForm", () => {
  it("returns no errors for valid input", () => {
    const errs = validateLoginForm("admin", "repolens@2026");
    expect(hasLoginErrors(errs)).toBe(false);
  });

  it("returns username error when username is empty", () => {
    const errs = validateLoginForm("", "somepassword");
    expect(errs.username).toBeDefined();
    expect(errs.password).toBeUndefined();
  });

  it("returns username error when username is whitespace only", () => {
    const errs = validateLoginForm("   ", "somepassword");
    expect(errs.username).toBeDefined();
  });

  it("returns password error when password is empty", () => {
    const errs = validateLoginForm("admin", "");
    expect(errs.password).toBeDefined();
    expect(errs.username).toBeUndefined();
  });

  it("returns both errors when both fields are empty", () => {
    const errs = validateLoginForm("", "");
    expect(errs.username).toBeDefined();
    expect(errs.password).toBeDefined();
    expect(hasLoginErrors(errs)).toBe(true);
  });

  it("hasLoginErrors returns false for empty errors object", () => {
    expect(hasLoginErrors({})).toBe(false);
  });
});

describe("validateRegisterForm", () => {
  it("returns no errors for valid input", () => {
    const errs = validateRegisterForm("alice", "password1", "password1");
    expect(hasRegisterErrors(errs)).toBe(false);
  });

  it("returns username error when username is empty", () => {
    const errs = validateRegisterForm("", "password1", "password1");
    expect(errs.username).toBeDefined();
  });

  it("returns username error when username is too short (< 3 chars)", () => {
    const errs = validateRegisterForm("ab", "password1", "password1");
    expect(errs.username).toBeDefined();
  });

  it("returns username error when username is too long (> 64 chars)", () => {
    const longName = "a".repeat(65);
    const errs = validateRegisterForm(longName, "password1", "password1");
    expect(errs.username).toBeDefined();
  });

  it("accepts username of exactly 3 chars", () => {
    const errs = validateRegisterForm("abc", "password1", "password1");
    expect(errs.username).toBeUndefined();
  });

  it("returns password error when password is empty", () => {
    const errs = validateRegisterForm("alice", "", "");
    expect(errs.password).toBeDefined();
  });

  it("returns password error when password is shorter than 6", () => {
    const errs = validateRegisterForm("alice", "abc", "abc");
    expect(errs.password).toBeDefined();
  });

  it("accepts password of exactly 6 chars", () => {
    const errs = validateRegisterForm("alice", "abcdef", "abcdef");
    expect(errs.password).toBeUndefined();
  });

  it("returns confirmPassword error when passwords do not match", () => {
    const errs = validateRegisterForm("alice", "password1", "password2");
    expect(errs.confirmPassword).toBeDefined();
  });

  it("no confirmPassword error when passwords match", () => {
    const errs = validateRegisterForm("alice", "password1", "password1");
    expect(errs.confirmPassword).toBeUndefined();
  });

  it("hasRegisterErrors returns false for empty errors object", () => {
    expect(hasRegisterErrors({})).toBe(false);
  });

  it("hasRegisterErrors returns true when any error exists", () => {
    expect(hasRegisterErrors({ username: "too short" })).toBe(true);
  });
});
