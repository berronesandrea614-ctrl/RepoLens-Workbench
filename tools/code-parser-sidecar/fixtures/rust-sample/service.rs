struct User { name: String }
struct UserService { users: Vec<User> }
impl UserService {
    fn add_user(&mut self, u: User) {
        self.validate(&u);
        self.users.push(u);
    }
    fn validate(&self, u: &User) -> bool {
        format_name(&u.name) != ""
    }
}
fn format_name(name: &str) -> String { name.trim().to_lowercase() }
fn create_default_service() -> UserService {
    let mut svc = UserService { users: vec![] };
    svc.add_user(User { name: "alkut".to_string() });
    svc
}
