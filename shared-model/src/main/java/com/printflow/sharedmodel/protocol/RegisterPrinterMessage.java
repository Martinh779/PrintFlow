package com.printflow.sharedmodel.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RegisterPrinterMessage extends SocketMessage {
    private String name;
    private String host;
    private int port;
    private boolean online = true;

    public RegisterPrinterMessage(String printerId, String name, String host, int port, boolean online) {
        super("REGISTER");
        setPrinterId(printerId);
        this.name = name;
        this.host = host;
        this.port = port;
        this.online = online;
    }
}
