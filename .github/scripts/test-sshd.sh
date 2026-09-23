#!/usr/bin/env bash
# The three sshds the test suite's live cases run against, and the SSH_TEST_* variables that point the tests at them:
# the target on 127.0.0.1:2222, the jump host on 127.0.0.1:2223 that the ProxyJump chain and Tunnels cases log
# in through on the way to it (SSH_TEST_JUMP_PORT), and on 127.0.0.1:2224 the target's twin that refuses agent
# forwarding, as a server with AllowAgentForwarding no does (SSH_TEST_NO_AGENT_PORT). Source this from the step that
# runs Gradle: it exports the variables into the caller's shell, and the test account's password exists only there
# and in the sshd's shadow entry, set through chpasswd and never written to a file, a log or the step's output. The
# first two are the pair the repository's build notes describe for a workstation (the twin's one test skips without
# it): password and public-key authentication, the sftp subsystem, TCP forwarding for the tunnel tests, one account
# on all three, and the jump host with host keys of its own so a hop's trust-on-first-use is its own decision.
set -euo pipefail

user="${SSH_TEST_ACCOUNT:-berth}"
port=2222
jump_port=2223
no_agent_port=2224
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

sudo mkdir -p /run/sshd
sudo ssh-keygen -A >/dev/null

# One config per instance: the port, the host keys (the system's for the target and its twin; the jump host's own,
# made here, so a hop presents different keys), the pid file and whether agent forwarding is allowed differ, the
# rest is the same.
write_config() {
    local dir="$1" instance_port="$2" ed25519="$3" second="$4" pidfile="$5" agent="${6:-yes}"
    sudo mkdir -p "$dir"
    sudo tee "$dir/sshd_config" >/dev/null <<EOF
Port $instance_port
ListenAddress 127.0.0.1
HostKey $ed25519
HostKey $second
PasswordAuthentication yes
KbdInteractiveAuthentication yes
PubkeyAuthentication yes
UsePAM no
PermitRootLogin no
AllowTcpForwarding yes
AllowAgentForwarding $agent
GatewayPorts no
AcceptEnv LANG LC_* COLORTERM TERM_PROGRAM
PrintLastLog no
Subsystem sftp /usr/lib/openssh/sftp-server
PidFile $pidfile
LogLevel VERBOSE
MaxStartups 100
MaxSessions 100
EOF
}
# MaxStartups and MaxSessions: the suite's test JVMs open connections in bursts (the live screenshot flows, the
# chain through the jump host, the tunnels, the transfers), and past ten unauthenticated connections at once the
# default MaxStartups 10:30:100 drops new ones at random, a handshake that fails once and passes on the rerun. A
# hundred of each is more than the suite ever holds open, and both sshds are reachable from this machine alone.
# PrintLastLog no: with it on, every pty login after the first opens with "Last login: <the previous test's
# moment> from 127.0.0.1", a line in the terminal that no two runs share, so the live screenshot frames never
# repeated; the shell's own prompt is what a frame should show.

start_instance() {
    local dir="$1" pidfile="$2"
    if ! { [ -f "$pidfile" ] && sudo kill -0 "$(cat "$pidfile")" 2>/dev/null; }; then
        sudo /usr/sbin/sshd -f "$dir/sshd_config"
    fi
}

write_config /etc/ssh/sshd_test "$port" /etc/ssh/ssh_host_ed25519_key /etc/ssh/ssh_host_rsa_key /tmp/sshd_test.pid
start_instance /etc/ssh/sshd_test /tmp/sshd_test.pid

[ -f /etc/ssh/sshd_jump/ssh_host_ed25519_key ] || { sudo mkdir -p /etc/ssh/sshd_jump && sudo ssh-keygen -q -t ed25519 -N '' -f /etc/ssh/sshd_jump/ssh_host_ed25519_key; }
[ -f /etc/ssh/sshd_jump/ssh_host_ecdsa_key ] || sudo ssh-keygen -q -t ecdsa -b 256 -N '' -f /etc/ssh/sshd_jump/ssh_host_ecdsa_key
write_config /etc/ssh/sshd_jump "$jump_port" /etc/ssh/sshd_jump/ssh_host_ed25519_key /etc/ssh/sshd_jump/ssh_host_ecdsa_key /tmp/sshd_jump.pid
start_instance /etc/ssh/sshd_jump /tmp/sshd_jump.pid

write_config /etc/ssh/sshd_noagent "$no_agent_port" /etc/ssh/ssh_host_ed25519_key /etc/ssh/ssh_host_rsa_key /tmp/sshd_noagent.pid no
start_instance /etc/ssh/sshd_noagent /tmp/sshd_noagent.pid

# Up, and answering the key, before any test asks; a server that is not is a failed step, not a skipped suite.
ssh_check() {
    ssh -p "$1" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
        -i "$keys/ed25519" "$user@127.0.0.1" true
}
for instance_port in "$port" "$jump_port" "$no_agent_port"; do
    for _ in $(seq 1 50); do ssh_check "$instance_port" 2>/dev/null && break; sleep 0.2; done
    ssh_check "$instance_port"
done
# The chain itself, once, the way the tests make it: through the jump host into the target.
ssh -o ProxyCommand="ssh -p $jump_port -W %h:%p -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i $keys/ed25519 $user@127.0.0.1" \
    -p "$port" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
    -i "$keys/ed25519" "$user@127.0.0.1" true
echo "test sshd listening on 127.0.0.1:$port, jump host on 127.0.0.1:$jump_port and no-agent twin on 127.0.0.1:$no_agent_port for $user"

export SSH_TEST_HOST=127.0.0.1
export SSH_TEST_PORT="$port"
export SSH_TEST_JUMP_PORT="$jump_port"
export SSH_TEST_NO_AGENT_PORT="$no_agent_port"
export SSH_TEST_USER="$user"
export SSH_TEST_PASSWORD="$password"
export SSH_TEST_KEY_FILE="$keys/ed25519"
export SSH_TEST_P256_KEY_FILE="$keys/p256"
