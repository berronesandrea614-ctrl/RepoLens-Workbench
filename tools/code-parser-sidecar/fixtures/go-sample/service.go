package sample

import "strings"

type User struct {
	Name string
}

type UserService struct {
	users []User
}

func (s *UserService) AddUser(u User) {
	s.Validate(u)
	s.users = append(s.users, u)
}

func (s *UserService) Validate(u User) bool {
	return FormatName(u.Name) != ""
}

func FormatName(name string) string {
	return strings.ToLower(strings.TrimSpace(name))
}

func CreateDefaultService() *UserService {
	svc := &UserService{}
	svc.AddUser(User{Name: "alkut"})
	return svc
}
