# Security

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private vulnerability
reporting on this repository ("Security" tab, "Report a vulnerability"). You should get a
first response within a week.

## Notes

- The cameras' Remote Viewfinder protocol is plain HTTP on an open camera Wi-Fi, without
  authentication beyond the camera's one-time "allow this device?" prompt. Anyone on that
  network can watch the live view and fetch pictures; that is the camera's design.
- finderscope talks only to the camera network it joined (or the address typed in), never to the
  internet. Its pairing id is a random value generated on the phone.
- The location permission is used only when *Tag pictures with the phone's location* is on, to
  send the position to the camera with each picture.
