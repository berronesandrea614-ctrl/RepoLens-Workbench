class UserService
  def add_user(user)
    validate(user)
  end
  def validate(user)
    format_name(user)
  end
  def format_name(name)
    name.strip.downcase
  end
end
