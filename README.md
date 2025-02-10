# cpu-time exporter
A java agent that exposes prometheus metrics with cpu-time metrics of used methods. The goal of this project is to support the data we receive from kepler on AKS. By combining the data of this project and that from kepler, we are able to get detailed information of power usage of our apps in any environment. 

Features:
- Java agent, no source code changes required
- Monitor cpu-time of methods to combine with i.e. kepler data

# Usage

Requirements
- Java 11+
- Maven

## Compile
To build the cpu-time-exporter, run:
```
./mvnw clean install -DskipTests
```

As alternative, you can also download the latest release on https://github.com/First8/cpu-time-exporter/releases

## Run
```
java -javaagent:cpu-time-exporter-$version.jar -jar yourJar.jar -Dcputimeexporter.config=config.properties
```

# License & Attribution

This project is licensed under the GNU General Public License v3.0 (GPL-3.0).

## Attribution

This project was inspired by and includes portions of code from JoularJX, an open-source project licensed under GPL-3.0.

As per the GPL v3 license, modifications and redistributions of this project must also comply with the same licensing terms. You can find a copy of the GPL v3 license in the LICENSE file of this repository or at gnu.org/licenses/gpl-3.0.