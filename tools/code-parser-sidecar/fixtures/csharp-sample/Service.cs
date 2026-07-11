namespace Sample {
    public interface IGreeter { string Greet(string name); }
    public class UserService {
        public void AddUser(string name) { Validate(name); }
        public bool Validate(string name) { return FormatName(name).Length > 0; }
        public static string FormatName(string name) { return name.Trim().ToLower(); }
    }
}
