package model;

import java.io.Serializable;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author M&M
 */
public class Artifact implements Serializable {

    private String name;
    private String creator;
    private String description;
    private String style;

    public Artifact(String name, String creator, String description, String style) {
        this.name = name;
        this.creator = creator;
        this.description = description;
        this.style = style;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }
}
