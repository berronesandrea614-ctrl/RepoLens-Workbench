export function formatName(name: string): string {
  return name.trim().toLowerCase();
}

export function greet(name: string): string {
  return "hello " + formatName(name);
}
