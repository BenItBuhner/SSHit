#!/usr/bin/env bash
# The sshd the test suite's live cases run against, on 127.0.0.1:2222, and the SSH_TEST_* variables that point the
# tests at it. Source this from the step that runs Gradle: it exports the variables into the caller's shell, and the
# test account's password exists only there and in the sshd's shadow entry, set through chpasswd and never written
# to a file, a log or the step's output. The same instance the repository's build notes describe for a workstation:
# password and public-key authentication, the sftp subsystem, TCP forwarding for the tunnel tests.
set -euo pipefail

user="${SSH_TEST_ACCOUNT:-berth}"
port=2222
keys="${RUNNER_TEMP:-/tmp}/berth-test-keys"

if [ ! -x /usr/sbin/sshd ]; then
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends openssh-server >/dev/null
fi

id -u "$user" >/dev/null 2>&1 || sudo useradd -m -s /bin/bash "$user"
password="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 20)"
if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::add-mask::$password"; fi
echo "$user:$password" | sudo chpasswd

# Two throwaway keys authorised for the account: Ed25519 for the public-key case, P-256 for the Keystore stand-in.
mkdir -p "$keys"
[ -f "$keys/ed25519" ] || ssh-keygen -q -t ed25519 -N '' -f "$keys/ed25519"
[ -f "$keys/p256" ] || ssh-keygen -q -t ecdsa -b 256 -N '' -f "$keys/p256"
sudo mkdir -p "/home/$user/.ssh"
cat "$keys/ed25519.pub" "$keys/p256.pub" | sudo tee "/home/$user/.ssh/authorized_keys" >/dev/null
sudo chown -R "$user:$user" "/home/$user/.ssh"
sudo chmod 700 "/home/$user/.ssh"
sudo chmod 600 "/home/$user/.ssh/authorized_keys"

sudo mkdir -p /run/sshd /etc/ssh/sshd_test
sudo ssh-keygen -A >/dev/null
sudo tee /etc/ssh/sshd_test/sshd_config >/dev/null <<EOF
Port $port
ListenAddress 127.0.0.1
HostKey /etc/ssh/ssh_host_ed25519_key
HostKey /etc/ssh/ssh_host_rsa_key
PasswordAuthentication yes
KbdInteractiveAuthentication yes
PubkeyAuthentication yes
UsePAM no
PermitRootLogin no
AllowTcpForwarding yes
GatewayPorts no
AcceptEnv LANG LC_* COLORTERM TERM_PROGRAM
Subsystem sftp /usr/lib/openssh/sftp-server
PidFile /tmp/sshd_test.pid
LogLevel VERBOSE
EOF
if ! { [ -f /tmp/sshd_test.pid ] && sudo kill -0 "$(cat /tmp/sshd_test.pid)" 2>/dev/null; }; then
    sudo /usr/sbin/sshd -f /etc/ssh/sshd_test/sshd_config
fi

# Up, and answering the key, before any test asks; a server that is not is a failed step, not a skipped suite.
ssh_check() {
    ssh -p "$port" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
        -i "$keys/ed25519" "$user@127.0.0.1" true
}
for _ in $(seq 1 50); do ssh_check 2>/dev/null && break; sleep 0.2; done
ssh_check
echo "test sshd listening on 127.0.0.1:$port for $user"

export SSH_TEST_HOST=127.0.0.1
export SSH_TEST_PORT="$port"
export SSH_TEST_USER="$user"
export SSH_TEST_PASSWORD="$password"
export SSH_TEST_KEY_FILE="$keys/ed25519"
export SSH_TEST_P256_KEY_FILE="$keys/p256"
