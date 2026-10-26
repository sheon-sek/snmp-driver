# net-snmp + OPC UA client used by the M1d system tests.
FROM python:3.12-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends snmp snmpd iproute2 \
 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir asyncua==1.1.5
WORKDIR /work
CMD ["sleep", "infinity"]
