/**
 * 登录/注册表单验证纯函数，与 React 解耦，便于单元测试。
 */
export interface LoginFormErrors {
  username?: string;
  password?: string;
}

export function validateLoginForm(username: string, password: string): LoginFormErrors {
  const errors: LoginFormErrors = {};
  if (!username || username.trim().length === 0) {
    errors.username = "用户名不能为空";
  }
  if (!password || password.length === 0) {
    errors.password = "密码不能为空";
  }
  return errors;
}

export function hasLoginErrors(errors: LoginFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

// ── Register validation ───────────────────────────────────────────────────────

export interface RegisterFormErrors {
  username?: string;
  password?: string;
  confirmPassword?: string;
}

export function validateRegisterForm(
  username: string,
  password: string,
  confirmPassword: string,
): RegisterFormErrors {
  const errors: RegisterFormErrors = {};
  const trimmed = username.trim();
  if (trimmed.length === 0) {
    errors.username = "用户名不能为空";
  } else if (trimmed.length < 3) {
    errors.username = "用户名不能少于 3 个字符";
  } else if (trimmed.length > 64) {
    errors.username = "用户名不能超过 64 个字符";
  }
  if (password.length === 0) {
    errors.password = "密码不能为空";
  } else if (password.length < 6) {
    errors.password = "密码不能少于 6 位";
  }
  if (confirmPassword !== password) {
    errors.confirmPassword = "两次密码不一致";
  }
  return errors;
}

export function hasRegisterErrors(errors: RegisterFormErrors): boolean {
  return Object.keys(errors).length > 0;
}
