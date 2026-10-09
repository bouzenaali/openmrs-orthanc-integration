# openmrs-orthanc-solution

A complete solution for integrating OpenMRS (EHR) and Orthanc (PACS) using Docker Compose to manage and view medical images.

## Table of Contents

- [Introduction](#introduction)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Configuration Details](#configuration-details)
- [Usage](#usage)

## Introduction

This repository provides a robust and reproducible setup for a PACS-EHR integration using Docker Compose. The solution deploys OpenMRS, a leading open-source electronic health record system, alongside Orthanc, a lightweight and powerful DICOM server. The two systems are linked via the OpenMRS Imaging Module, allowing you to manage and view patient-associated DICOM images directly from the OpenMRS patient dashboard.

## Features

- **Docker-based Deployment:** All services run in isolated Docker containers, ensuring consistency and ease of setup on any host with Docker.
- **OpenMRS Reference Application:** A fully functional OpenMRS instance with a MySQL database.
- **Orthanc PACS Server:** An Orthanc instance with a PostgreSQL backend for efficient DICOM storage and indexing.
- **Pre-configured Integration:** The setup includes the necessary modules and configuration to link OpenMRS and Orthanc.
- **ARM64 Architecture Support:** The provided Docker images are compatible with Apple Silicon (M1/M2/M3) and other ARM64 systems.

## Prerequisites

Before you begin, ensure you have the following software installed:

- **Docker Desktop:** The official Docker application for your operating system (Windows, macOS, or Linux).
- **Git:** For cloning this repository.

## Project Structure

```
.
├── openmrs-module-imaging/
├── .gitignore
├── .gitmodules
├── openmrs-docker-compose.yml
├── orthanc-docker-compose.yml
└── README.md
```
- `openmrs-docker-compose.yml`: The file to orchestrate the OpenMRS application and its MySQL database.
- `orthanc-docker-compose.yml`: The file to orchestrate the Orthanc PACS server and its PostgreSQL database.
- `openmrs-module-imaging/`: A directory containing the OpenMRS Imaging Module's files.

## Getting Started

Follow these steps to get the OpenMRS and Orthanc solution up and running.

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/bouzenaali/openmrs-orthanc-integration
    cd openmrs-orthanc-integration
    ```

2.  **Start the Containers:**
    This command will build the images and start all the services in the background.
    ```bash
    docker compose up -d
    ```
    *Wait a few minutes for all services to become healthy before proceeding.*

3.  **Initial OpenMRS Setup:**
    Open your web browser and navigate to the OpenMRS installation wizard.
    ```bash
    http://localhost:8080/openmrs
    ```
    Follow the on-screen instructions to complete the setup. Use the default values, but be sure to connect to the `openmrs-db` container.

4.  **Install Required Modules:**
    - Download the **`webservices.rest-2.48.0.omod`** from [modules.openmrs.org](https://modules.openmrs.org/).
    - Navigate to **Administration > Manage Modules** in OpenMRS.
    - Click "Upload Module," select the downloaded file, and install it.
    - Similarly, download and install the **`openmrs-module-imaging`** module from its source.

5.  **Configure OpenMRS-Orthanc Integration:**
    - After installing the modules, go to the module settings (or global properties) for the Imaging Module.
    - Fill in the configuration details as follows:
        - **ID:** `OpenMRS_Orthanc_PACS`
        - **URL:** `http://orthanc-app:8042`
        - **USERNAME:** `orthanc`
        - **PASSWORD:** `orthanc_admin_password`
        - **PROXY_URL:** (Leave this field blank)

## Configuration Details

This section outlines the key configuration settings in your `docker-compose` and `orthanc.json` files.

### `orthanc-docker-compose.yml` Environment
- **Authentication:** Orthanc is configured with HTTP authentication enabled via environment variables:
  ```yaml
  environment:
    ORTHANC__AUTHENTICATION_ENABLED: "true"
    ORTHANC__REGISTERED_USERS: '{"orthanc": "orthanc_admin_password"}'
orthanc.json

**Plugins**: The Plugins field is set to an empty array to avoid deployment errors:

```JSON
"Plugins": [],
```

PostgreSQL: Orthanc is configured to use the orthanc-db container for its data:

```JSON
"PostgreSQL": {
  "EnableIndex": true,
  "EnableStorage": true,
  "Host": "orthanc-db",
  "Database": "orthanc"
}
```
## Usage

To test the integration, follow these steps:

1.  **Access Dashboards:**

      - OpenMRS: `http://localhost:8080/openmrs`
      - Orthanc Explorer: `http://localhost:8042`

2.  **Send a DICOM Image:**

      - Use a DICOM client or `storescu` to send a test image to Orthanc. The DICOM AE Title is `ORTHANC`, and the address is `localhost:4242`.
      - **Important:** Ensure the DICOM Patient ID `(0010,0020)` in the image matches the OpenMRS ID of a patient you create.

3.  **View Image in OpenMRS:**

      - In OpenMRS, navigate to the patient dashboard for the patient linked to the DICOM image.
      - The Imaging Module should now display the study, allowing you to view the images from Orthanc.

### Phase 2: Deployment and Security

This phase is about securing your applications with a reverse proxy and deploying the solution. A **reverse proxy** acts as an intermediary, sitting in front of your applications to handle requests. This allows you to centralize security, enabling HTTPS encryption and preventing direct access to your application containers.

-----

### 1\. Set Up the Reverse Proxy

We'll use **Nginx Proxy Manager**, which is a great Docker-based solution with a simple graphical interface.

1.  **Create a Directory:** Open your terminal and create a new directory for the proxy.

    ```bash
    mkdir nginx-proxy-manager
    cd nginx-proxy-manager
    ```

2.  **Create a Docker Compose File:** Create a file named `docker-compose.yml` with the following content. This file defines the Nginx Proxy Manager service and its settings.

    ```yaml
    version: '3.8'

    services:
      app:
        image: 'jc21/nginx-proxy-manager:latest'
        restart: unless-stopped
        ports:
          - '80:80'
          - '443:443'
          - '81:81'
        volumes:
          - ./data:/data
          - ./letsencrypt:/etc/letsencrypt
    ```

      * `ports`: This maps the host's ports (`80`, `443`, `81`) to the container's ports. Port `80` is for HTTP, `443` is for HTTPS, and `81` is for the proxy's web interface.
      * `volumes`: This ensures that the proxy's data and SSL certificates persist even if you restart the container.

3.  **Start the Proxy:** Run the following command in the terminal from inside the `nginx-proxy-manager` directory.

    ```bash
    docker compose up -d
    ```

    The `-d` flag runs the containers in the background.

-----

### 2\. Configure the Reverse Proxy and Enable HTTPS 🔒

1.  **Access the Web Interface:** Open a web browser and navigate to `http://<your_server_ip>:81`. This will bring you to the Nginx Proxy Manager login screen.
2.  **Log In and Change Credentials:** Log in with the default credentials:
      * **Email:** `admin@example.com`
      * **Password:** `changeme`
        You will be prompted to immediately change these to secure credentials.
3.  **Set Up Proxy Hosts:** This is where you connect your domain names to your Docker containers. For OpenMRS and Orthanc, you'll create two separate "Proxy Hosts."
      * For each host, you'll need to enter the **domain name** (e.g., `openmrs.hospital.lan` or `orthanc.hospital.lan`).
      * You'll also need the **internal hostname** of the target container (`openmrs-app` or `orthanc-app`) and its internal port (`8080` for OpenMRS, `8042` for Orthanc).
      * Under the "SSL" tab, you'll request a **free Let's Encrypt SSL certificate**. This process is automated and will enable HTTPS.

-----

### 3\. Deploy OpenMRS and Orthanc

Now that the security layer is in place, you can deploy your solution.

1.  **Update the `docker-compose.yml`:** Edit your solution's `docker-compose.yml` file.

      * **Change Passwords:** Update all default passwords for MySQL, PostgreSQL, and Orthanc to strong, secure passwords. This is a critical security measure.
      * **Network Configuration:** Add the `nginx-proxy-manager` network to your OpenMRS and Orthanc services. This allows the proxy to "see" your application containers.
      * **OpenMRS Integration:** Change the Orthanc URL within the OpenMRS configuration to use the new, secure HTTPS domain (e.g., `https://orthanc.hospital.lan`) instead of the old `http://orthanc-app:8042`.

2.  **Deploy the Solution:** In the terminal, navigate to the directory containing your updated `docker-compose.yml` file and run:

    ```bash
    docker compose up -d
    ```

    This will start all your services, and they will now be accessible securely through the domains you configured in Nginx Proxy Manager.
