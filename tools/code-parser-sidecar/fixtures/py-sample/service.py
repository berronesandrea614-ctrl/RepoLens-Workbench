from util import format_name


class UserService:
    def __init__(self):
        self.users = []

    def add_user(self, user):
        self.validate(user)
        self.users.append(user)

    def validate(self, user):
        return format_name(user["name"]) != ""


def create_default_service():
    svc = UserService()
    svc.add_user({"name": "alkut"})
    return svc
