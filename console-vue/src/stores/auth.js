import Cookies from 'js-cookie'

const TOKEN_KEY = 'token'
const USERNAME_KEY = 'username'
const USER_ID_KEY = 'userId'

export function getToken() {
  return Cookies.get(TOKEN_KEY) ?? ''
}

export function getUsername() {
  return Cookies.get(USERNAME_KEY) ?? ''
}

export function getUserId() {
  return Cookies.get(USER_ID_KEY) ?? ''
}

export function isLoggedIn() {
  return Boolean(getToken() && getUsername())
}

export function setSession({ token, username, userId }) {
  if (token) Cookies.set(TOKEN_KEY, token)
  if (username) Cookies.set(USERNAME_KEY, username)
  if (userId) Cookies.set(USER_ID_KEY, userId)
}

export function clearSession() {
  Cookies.remove(TOKEN_KEY)
  Cookies.remove(USERNAME_KEY)
  Cookies.remove(USER_ID_KEY)
}
