// Platzhalter für libusb (CarPlay-Dongle): findet nie ein Gerät. GPL-3.0-or-later
#pragma once
#include <sys/types.h>
#include <cstdint>
struct libusb_context; struct libusb_device; struct libusb_device_handle;
struct libusb_device_descriptor { uint16_t idVendor = 0, idProduct = 0; };
struct libusb_endpoint_descriptor { uint8_t bEndpointAddress = 0, bmAttributes = 0; };
struct libusb_interface_descriptor { uint8_t bNumEndpoints = 0; const libusb_endpoint_descriptor *endpoint = nullptr; };
struct libusb_interface { const libusb_interface_descriptor *altsetting = nullptr; };
struct libusb_config_descriptor { const libusb_interface *interface = nullptr; };
enum { LIBUSB_ENDPOINT_IN = 0x80, LIBUSB_TRANSFER_TYPE_BULK = 2, LIBUSB_ERROR_TIMEOUT = -7, LIBUSB_TRANSFER_TYPE_MASK = 3 };
inline int libusb_init(libusb_context **) { return -1; }
inline void libusb_exit(libusb_context *) {}
inline ssize_t libusb_get_device_list(libusb_context *, libusb_device ***) { return 0; }
inline void libusb_free_device_list(libusb_device **, int) {}
inline int libusb_get_device_descriptor(libusb_device *, libusb_device_descriptor *) { return -1; }
inline int libusb_open(libusb_device *, libusb_device_handle **) { return -1; }
inline void libusb_close(libusb_device_handle *) {}
inline libusb_device *libusb_get_device(libusb_device_handle *) { return nullptr; }
inline int libusb_get_active_config_descriptor(libusb_device *, libusb_config_descriptor **) { return -1; }
inline void libusb_free_config_descriptor(libusb_config_descriptor *) {}
inline int libusb_claim_interface(libusb_device_handle *, int) { return -1; }
inline int libusb_release_interface(libusb_device_handle *, int) { return 0; }
inline int libusb_set_auto_detach_kernel_driver(libusb_device_handle *, int) { return 0; }
inline int libusb_bulk_transfer(libusb_device_handle *, unsigned char, unsigned char *, int, int *, unsigned int) { return -1; }
inline const char *libusb_error_name(int) { return "no usb"; }
