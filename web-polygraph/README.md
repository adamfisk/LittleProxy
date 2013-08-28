# Benchmarking LittleProxy with Web Polygraph

LittleProxy has been benchmarked using
[Web Polygraph](http://www.web-polygraph.org/).

We used the [Polymix-4](http://www.web-polygraph.org/docs/workloads/polymix-4/)
workload.

## How to Run the Benchmarks

### Install Web Polygraph

We installed this on two separate machines on the same LAN.  We used one machine
as the server and the other machine as the client.

1. Download version 4.3.2 from [here](http://www.web-polygraph.org/downloads/).
2. `tar -zxf polygraph-4.3.2-src.tgz`
3. `cd polygraph-4.3.2`
4. `./configure`
5. `make`
6. `make install` (no sudo required)

### Configure Your Workload

Edit polymix-4.pg.

1. Put your client's ip address on line 15 
2. Put your server's ip address on line 19
3. Set up the peak benchmark rate on line 32
4. Set up the fill rate on line 37
5. Set up the proxy cache size on line 41 (we set it to 0GB since
   LittleProxy doesn't cache)
6. Set up your dns host(s) on line 46 (we used Google's at `8.8.8.8:53`)
