import time
import RPi.GPIO as GPIO
import binascii
import threading
from pn532pi import Pn532, Pn532Hsu
from time import sleep

GPIO.setwarnings(False)
GPIO.setmode(GPIO.BCM)

buzzer_pin = 18
RED_PIN = 17
GREEN_PIN = 27
BLUE_PIN = 22
RELAY = 23

GPIO.setup(buzzer_pin, GPIO.OUT)
GPIO.setup(RED_PIN, GPIO.OUT)
GPIO.setup(GREEN_PIN, GPIO.OUT)
GPIO.setup(BLUE_PIN, GPIO.OUT)
GPIO.setup(RELAY, GPIO.OUT)

red_pwm = GPIO.PWM(RED_PIN, 1000)
green_pwm = GPIO.PWM(GREEN_PIN, 1000)
blue_pwm = GPIO.PWM(BLUE_PIN, 1000)

red_pwm.start(100)  
green_pwm.start(100)
blue_pwm.start(100)

stop_threads = False
nfc_detected = threading.Event()

PN532_HSU = Pn532Hsu(Pn532Hsu.RPI_MINI_UART)
nfc = Pn532(PN532_HSU)

def buzzer_control(duration):
    GPIO.output(buzzer_pin, GPIO.HIGH)
    time.sleep(duration)
    GPIO.output(buzzer_pin, GPIO.LOW)

def play_buzzer_pattern():
    pattern = [0.1, 0.1, 0.2, 0.1, 0.1, 0.4, 0.1, 0.3, 0.1]
    for duration in pattern:
        buzzer_control(duration)
        time.sleep(0.1)

def play_error_sound():
    pattern = [0.5, 0.5, 0.5, 0.5, 0.5]
    for duration in pattern:
        buzzer_control(duration)
        time.sleep(0.1)

def setup():
    print("-------Ascultare mesaje HCE de la Android--------")
    nfc.begin()
    versiondata = nfc.getFirmwareVersion()
    if not versiondata:
        raise RuntimeError("Nu s-a găsit placa PN53x")
    print(f"S-a găsit chip PN5 {(versiondata >> 24) & 0xFF:#x} Firmware ver. {(versiondata >> 16) & 0xFF}.{(versiondata >> 8) & 0xFF}")
    nfc.SAMConfig()

def set_led(red, green, blue):
    red_pwm.ChangeDutyCycle(100 - red)
    green_pwm.ChangeDutyCycle(100 - green)
    blue_pwm.ChangeDutyCycle(100 - blue)

def fade_blue_led():
    global stop_threads
    while not stop_threads:
        for duty_cycle in range(100, -1, -1):  
            if stop_threads:
                return
            blue_pwm.ChangeDutyCycle(duty_cycle)
            time.sleep(0.02)
        time.sleep(0.5)
        for duty_cycle in range(0, 101):  
            if stop_threads:
                return
            blue_pwm.ChangeDutyCycle(duty_cycle)
            time.sleep(0.02)
        time.sleep(0.5)

def nfc_detection_thread():
    global stop_threads
    while not stop_threads:
        if nfc.inListPassiveTarget():
            nfc_detected.set()
            return 
        time.sleep(0.1)  

def parse_ndef_message(data):
    try:
        if data[-2:] == b'\x90\x00':
            data = data[:-2]
        if len(data) < 3:
            raise ValueError("Mesaj prea scurt")

        payload_length = data[2]
        payload = data[6:6 + payload_length]
        text = payload[3:].decode('utf-8')

        print(f"Mesajul NDEF decodat: {text}")

        if "unlock" in text:
            print("Mesajul conține 'unlock'")
            set_led(0, 100, 0) 
            GPIO.output(RELAY, GPIO.LOW)  
            play_buzzer_pattern()  
            print("Acces permis: LED verde aprins, Releu activat, Buzzer pornit")
            time.sleep(5)  
            GPIO.output(RELAY, GPIO.HIGH)  
            print("Solenoid închis după 5 secunde")
        else:
            print("Mesajul nu conține 'unlock'")
            set_led(100, 0, 0) 
            GPIO.output(RELAY, GPIO.HIGH)  
            play_error_sound()  
            print("Acces respins: LED roșu aprins, Releu dezactivat, Sunet de eroare activat")
    except Exception as e:
        print(f"Eroare la procesarea mesajului NDEF: {e}")
        set_led(100, 0, 0) 
        GPIO.output(RELAY, GPIO.HIGH)  
        play_error_sound()  
        print("Eroare: LED roșu aprins, Releu dezactivat, Sunet de eroare activat")
    finally:
        time.sleep(1) 
        set_led(0, 0, 100)  
        GPIO.output(RELAY, GPIO.HIGH)  

def listen_for_android():
    global stop_threads
    print("Se așteaptă un dispozitiv Android cu HCE...")
    
    stop_threads = False
    nfc_detected.clear()
    
    nfc_thread = threading.Thread(target=nfc_detection_thread)
    fade_thread = threading.Thread(target=fade_blue_led)
    
    nfc_thread.start()
    fade_thread.start()

    nfc_detected.wait()  
    stop_threads = True
    
    nfc_thread.join()
    fade_thread.join()

    print("S-a detectat un dispozitiv!")
    aid = bytearray([0xD2, 0x96, 0x00, 0x00, 0x85, 0x01, 0x01])
    select_apdu = bytearray([0x00, 0xA4, 0x04, 0x00, len(aid)] + list(aid) + [0x00])

    success, response = nfc.inDataExchange(select_apdu)
    if success:
        print("AID selectat cu succes")
        print(f"Răspuns: {binascii.hexlify(response)}")

        read_binary_apdu = bytearray([0x00, 0xB0, 0x00, 0x00, 0x00])
        success, received_data = nfc.inDataExchange(read_binary_apdu)
        if success and received_data:
            print("Date primite de la Android:")
            print(binascii.hexlify(received_data))
            parse_ndef_message(received_data)
        else:
            print("Eroare la citirea datelor sau date goale")
            set_led(100, 0, 0)  
            GPIO.output(RELAY, GPIO.HIGH)  
            play_error_sound()  
            print("Eroare la citire: LED roșu aprins, Releu dezactivat, Sunet de eroare activat")
    else:
        print("Eroare la selectarea AID-ului")
        set_led(100, 0, 0)  
        GPIO.output(RELAY, GPIO.HIGH)  
        play_error_sound()  
        print("Eroare AID: LED roșu aprins, Releu dezactivat, Sunet de eroare activat")

if __name__ == '__main__':
    try:
        setup()
        while True:
            set_led(0, 0, 100)  
            GPIO.output(RELAY, GPIO.HIGH)  
            listen_for_android()
    except KeyboardInterrupt:
        print("Program întrerupt de utilizator")
    finally:
        stop_threads = True
        set_led(0, 0, 0) 
        GPIO.output(RELAY, GPIO.HIGH) 
        red_pwm.stop()
        green_pwm.stop()
        blue_pwm.stop()
        GPIO.cleanup()