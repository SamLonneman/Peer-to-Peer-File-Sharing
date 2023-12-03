# Peer to Peer File Sharing
### CNT 4007 Computer Networking Fundamentals, Fall 2023

## Group 45
Samuel Lonneman (slonneman@ufl.edu)

Jacob Immich (jacobimmich@ufl.edu)

Darren Wang (darrenwang@ufl.edu)

## Individual Contributions
**Samuel Lonneman** implemented the entirety of the peer to peer file sharing protocol, recorded the demo video, and managed this git repository.

**Jacob Immich** created the 2 shell scripts which can optionally be used to deploy the project to the departmental server and start the remote peers.

**Darren Wang** did not contribute.

## Video Demo
https://youtu.be/SHbVEdrUlTM

## Achievements
Fortunately, we were able to complete all requirements of the project. As you can see in our video demo, we created a program which conforms to the protocol set forth in the project description and successfully ran it on the CISE departmental Linux servers to transfer a file of over 20MB from 1 peer to 5 other peers, while making detailed logs along the way. There were no required aspects of the project which we failed to complete.

## Playbook (How to run our project)
In order to run the project on the department Linux servers, you will first need to prepare a tar archive to transfer onto the server. In the root directory of this repository, use the following command in your terminal to generate the tar archive:

```
tar -cvf PeerToPeer.tar PeerToPeer
```

Now, you will need to transfer this tar file onto the departmental Linux server using the method of your choice. For example one could use SFTP:

```
sftp <gatorlink_username>@storm.cise.ufl.edu
> lcd <tar_file_location>
> put PeerToPeer.tar
> exit
```

Now, SSH into the server, extract the contents of the archive, and compile the PeerProcess java program.

```
ssh <gatorlink_username>@storm.cise.ufl.edu
> tar -xvf PeerToPeer.tar
> cd PeerToPeer
> javac PeerProcess.java
> exit
```

The program is now set up and ready to be run. You should now open 6 separate terminals on your home computer and SSH into the six departmental linux machines `lin114-00.cise.ufl.edu` through `lin114-05.cise.ufl.edu`, then start all programs back to back:

```
ssh <gatorlink_username>@lin114-00.cise.ufl.edu
> cd PeerToPeer
> java PeerProcess 1001
--------------------------------------------------------
ssh <gatorlink_username>@lin114-01.cise.ufl.edu
> cd PeerToPeer
> java PeerProcess 1002
--------------------------------------------------------
.
.
.
--------------------------------------------------------
ssh <gatorlink_username>@lin114-05.cise.ufl.edu
> cd PeerToPeer
> java PeerProcess 1006
```

Transferred files will appear in each peer's individual directory, and log files will be generated in `~/PeerToPeer`

**Note:** To run locally, simply replace each hostname in `PeerInfo.cfg` with `localhost`, then compile and run in multiple terminal instances on your local machine.

## Playbook (alternative)
TODO: JACOB please fill in this section

## Project Overview
The main goal of this project is to implement the principles of a peer to peer file sharing system. Specifically, the protocol below is a simplified version of BitTorrent. One of the main focuses of this project is the unique choking-unchoking mechanism that happens between peers. The algorithm of a single peer process and a brief description of the protocol are explained below.

## Peer Process
1. Start by reading in parameters and scenario information from the common config file and PeerInfo file.
2. Create a structure in memory to hold the file contents. If the peer already has the file, load it into memory.
3. Make a TCP connection with each other peer in the list and perform a handshake.
4. Begin receiving messages and responding by sending messages to facilitate the transfer of file data according to the protocol's details.
5. Continuously run two timers, one for updating the preferred neighbors, and one for updating the optimistically unchoked neighbor, each according to intervals defined in the common config file.
6. Once receiving the entire file, continue participating altruistically, sending pieces to those peers who request them.
7. Only terminate once every peer in the pool has received the entire file.

## Protocol Description
In the following description I say "you" to refer to some peer of interest.
1. Following the HANDSHAKE with a new peer, send a BITFIELD message to that peer informing them of which pieces of the file you currently have.
2. Upon receiving a BITFIELD/HAVE message, update your record of that peer's bitfield and determine whether that peer has any pieces which you still need. Respond by sending an INTERETSTED or NOT INTERESTED message accordingly.
3. If you receive an UNCHOKE message, respond with a REQUEST message, requesting some piece that peer has which you still need.
4. If you receive a request message, respond with a PIECE message with that given piece, assuming the peer requesting is unchoked.
5. Upon receipt of a PIECE message, store the piece and send HAVE message to all peers, informing them you have it. Also, send another REQUEST to the peer if still unchoked.
6. After each timer goes off to update preferred neighbors/optimistically unchoked neighbor, send CHOKE messages to those who are no longer preferred and UNCHOKE to those who become preferred.
