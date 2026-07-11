import { formatName } from "./util";

export interface User {
  id: number;
  name: string;
}

export class UserService {
  private users: User[] = [];

  addUser(user: User): void {
    this.validate(user);
    this.users.push(user);
  }

  validate(user: User): boolean {
    return user.id > 0 && formatName(user.name).length > 0;
  }

  findUser(id: number): User | undefined {
    return this.users.find((u) => u.id === id);
  }
}

export function createDefaultService(): UserService {
  const svc = new UserService();
  svc.addUser({ id: 1, name: "alkut" });
  return svc;
}
