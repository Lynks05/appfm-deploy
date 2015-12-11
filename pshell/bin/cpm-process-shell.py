#!/usr/bin/python

import sys
import zmq
import time
import subprocess
import shlex

# docker have their network by default nat, so we need to get the gateway as host to send msg to cpm core server
rawnetinfo = subprocess.check_output(["netstat", "-r"])
netinfos = rawnetinfo.split("\n")
for netinfo in netinfos :
	elts = netinfo.split(" ")
	if(len(elts)==1):
		break
	i = 1
	while(len(elts)>i and elts[i]==""):
		i+=1
	if(elts[0] == "default" or elts[0]=="0.0.0.0"):
		host = elts[i]



if len(sys.argv) > 5 :
	dockerized = sys.argv[1]
	if dockerized!="true" :
		host = "localhost"
	pid = sys.argv[2]
	name = sys.argv[3]
	port = sys.argv[4]
	cmd = " ".join(sys.argv[5:])
else :
  exit()


connectionInfo = "tcp://"+host+":"+port

context = zmq.Context()

sock = context.socket(zmq.PUSH)
sock.connect(connectionInfo)

processinfo = open("/tmp/pinfo"+pid,"w")
outfile = open("/tmp/out"+pid,"w")
errfile = open("/tmp/err"+pid,"w")

processinfo.write(cmd+"\n")


exitval = subprocess.call(cmd,shell=True,stdout=outfile,stderr=errfile)


processinfo.write(str(exitval))
processinfo.close()
outfile.close()
errfile.close()

message = name+"\nFINISHED\n"+str(exitval)
sock.send(message)

